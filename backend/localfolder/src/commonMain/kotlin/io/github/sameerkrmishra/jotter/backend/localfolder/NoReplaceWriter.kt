package io.github.sameerkrmishra.jotter.backend.localfolder

import io.github.sameerkrmishra.jotter.backend.api.BlobAlreadyExistsException
import io.github.sameerkrmishra.jotter.backend.api.RESERVED_SEGMENT_PREFIX
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.use
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** The file whose lock serialises publishing across processes. */
internal const val LOCK_NAME: String = "${RESERVED_SEGMENT_PREFIX}lock"

/** The start of a temporary file's name. */
internal const val TEMPORARY_PREFIX: String = "${RESERVED_SEGMENT_PREFIX}tmp-"

/** What [systemHardLink] did. */
internal enum class LinkResult {
    /** The target now exists, with the source's content. */
    LINKED,

    /** The target already existed, and nothing changed. */
    TARGET_EXISTS,

    /**
     * The link was not made for any other reason. Usually the file system or a security policy has
     * no hard links, but it can be a real I/O error, which the fallback then runs into as well.
     */
    NOT_LINKED,
}

/**
 * A fcntl lock belongs to the process, so two threads would both hold it, and it is lost when any
 * thread closes the file. This makes them take turns, for all folders: publishing is one rename.
 */
private val publishTurn = reentrantLock()

/**
 * Creates files below [root] that appear whole or not at all, and never replace anything. The steps
 * and the reasons for them are in `docs/adr/0012-local-folder-publishing.md`.
 */
internal class NoReplaceWriter(
    private val root: Path,
    private val fileSystem: FileSystem,
    private val access: FolderAccess,
) {
    /**
     * Creates the file [segments] below [root], and the directories above it, with the content
     * [bytes]. A symbolic link above it counts as a file. [beforePublishing] runs as the last step
     * before the file appears, and may stop that by throwing.
     *
     * A failure after the file has appeared leaves it in place. It is complete and immutable, and
     * a reader may have seen it, so removing it would be worse. See [syncAbove].
     *
     * @throws BlobAlreadyExistsException if the path exists.
     * @throws IOException if the file system fails, possibly after the file has appeared.
     */
    fun write(
        segments: List<String>,
        bytes: ByteArray,
        beforePublishing: () -> Unit,
    ) {
        val parent = createDirectories(segments.dropLast(1))
        val target = parent / segments.last()
        val temporary = parent / temporaryName()
        try {
            writeDurably(temporary, bytes)
            publishTurn.withLock {
                access.lock(root / LOCK_NAME) { lockFailure ->
                    beforePublishing()
                    publish(temporary, target, segments.joinToString("/"), lockFailure)
                }
            }
        } finally {
            deleteQuietly(temporary)
        }
        syncAbove(segments)
    }

    /**
     * Makes the entries that lead to the file [segments] durable, from its directory up to [root].
     * A write that failed here left its file in place, so a retry of the write comes here too.
     *
     * @throws IOException if the file system fails.
     */
    fun syncAbove(segments: List<String>) {
        segments
            .dropLast(1)
            .runningFold(root) { directory, segment -> directory / segment }
            .asReversed()
            .forEach(access.syncDirectory)
    }

    private fun createDirectories(segments: List<String>): Path =
        segments.fold(root) { directory, segment ->
            val next = directory / segment
            val entry = access.stat(next)
            when {
                entry == null -> createDirectory(next)
                !entry.isDirectory -> throw IOException("$next is not a directory")
            }
            next
        }

    private fun createDirectory(directory: Path) {
        try {
            fileSystem.createDirectory(directory, mustCreate = false)
        } catch (e: IOException) {
            // On Native okio fails if a concurrent writer has just made it, despite mustCreate.
            if (access.stat(directory)?.isDirectory != true) throw e
        }
    }

    // Not kotlin.random: on Native, threads that call Random.Default at once can get equal values.
    @OptIn(ExperimentalUuidApi::class)
    private fun temporaryName(): String = "$TEMPORARY_PREFIX${Uuid.random()}"

    private fun writeDurably(
        temporary: Path,
        bytes: ByteArray,
    ) {
        fileSystem.openReadWrite(temporary, mustCreate = true, mustExist = false).use { handle ->
            handle.write(0, bytes, 0, bytes.size)
            handle.flush() // fsync
        }
    }

    // A hard link never replaces anything by itself, so it needs no lock. Without hard links (FAT,
    // exFAT, Android's app storage) the check and the rename are safe only because every writer
    // holds the lock here, so without the lock they must not happen. The check must be strict: a
    // lookup that fails must never read as a free name, or the rename replaces a file.
    private fun publish(
        temporary: Path,
        target: Path,
        path: String,
        lockFailure: IOException?,
    ) {
        when (access.hardLink(temporary, target)) {
            LinkResult.LINKED -> Unit
            LinkResult.TARGET_EXISTS -> throw BlobAlreadyExistsException(path)
            LinkResult.NOT_LINKED -> publishByRename(temporary, target, path, lockFailure)
        }
    }

    private fun publishByRename(
        temporary: Path,
        target: Path,
        path: String,
        lockFailure: IOException?,
    ) {
        if (lockFailure != null) {
            throw IOException("No hard link and no lock: ${lockFailure.message}")
        }
        if (access.stat(target) != null) throw BlobAlreadyExistsException(path)
        fileSystem.atomicMove(temporary, target)
    }

    // After a hard link the temporary name is left over. After a failure this must not replace the
    // exception that says what went wrong, and a stale temporary file does no harm.
    private fun deleteQuietly(temporary: Path) {
        try {
            fileSystem.delete(temporary, mustExist = false)
        } catch (_: IOException) {
            // Nothing to do; see above.
        }
    }
}
