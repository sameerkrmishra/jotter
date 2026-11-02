package io.github.sameerkrmishra.jotter.backend.localfolder

import io.github.sameerkrmishra.jotter.backend.api.BackendUnavailableException
import io.github.sameerkrmishra.jotter.backend.api.BlobAlreadyExistsException
import io.github.sameerkrmishra.jotter.backend.api.BlobInfo
import io.github.sameerkrmishra.jotter.backend.api.BlobNotFoundException
import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreException
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreFailureException
import io.github.sameerkrmishra.jotter.backend.api.RESERVED_SEGMENT_PREFIX
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.api.UnreachableReason
import io.github.sameerkrmishra.jotter.backend.api.isValidBlobSegment
import io.github.sameerkrmishra.jotter.backend.api.requireValidBlobPath
import io.github.sameerkrmishra.jotter.backend.api.requireValidBlobPrefix
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.IOException
import okio.Path

/** The file that marks a folder as a store. See [LocalFolderBlobStore.initialize]. */
internal const val MARKER_NAME: String = "${RESERVED_SEGMENT_PREFIX}store"

/**
 * A [BlobStore] over the directory [root]: a USB stick, or a folder that some other tool
 * replicates. See `docs/adr/0012-local-folder-publishing.md` for how `writeNew` keeps its promises.
 *
 * Before first use the folder must be set up with [initialize]. Until then the store counts as
 * unreachable, which is how the bare mount point of an unplugged drive is told from an empty store.
 *
 * Inside a store symbolic links are not followed: [list] and [read] act as if they were not there,
 * and [writeNew] refuses a path through one. [root] itself may be one. Files that this store cannot
 * name, such as one with a colon in its name, are ignored in the same way.
 *
 * Only a real file path works, so this does not cover an Android `content://` folder.
 */
public class LocalFolderBlobStore internal constructor(
    private val root: Path,
    private val fileSystem: FileSystem,
    private val access: FolderAccess,
    private val dispatcher: CoroutineDispatcher,
) : BlobStore {
    /** Creates a store over the directory [root]. This touches nothing on disk. */
    public constructor(root: Path) : this(root, systemFileSystem, FolderAccess(), ioDispatcher)

    private val writer = NoReplaceWriter(root, fileSystem, access)
    private val missing = Reachability.Unreachable(UnreachableReason.MISSING, "$root is missing")

    /**
     * Sets [root], which must exist, up as a store. Doing so again is harmless, and is how to
     * retry after a failure. Call this when the user adds the folder as a backend. Never call it
     * because [ping] said [UnreachableReason.NOT_INITIALIZED] during sync, or a bare mount point
     * becomes a store.
     *
     * @throws BackendUnavailableException if [root] does not exist.
     * @throws BlobStoreFailureException if the file system fails.
     */
    public suspend fun initialize(): Unit =
        io("initialize", checkStoreOnFailure = false) {
            if (access.stat(root) == null) throw BackendUnavailableException(missing)
            if (access.stat(root / MARKER_NAME) == null) {
                // Not mustCreate: a concurrent initialize has the same aim.
                fileSystem.write(root / MARKER_NAME, mustCreate = false) { }
            }
            // Also when the marker is there: a retry may follow a failed sync.
            access.syncDirectory(root)
        }

    override suspend fun list(prefix: String): List<BlobInfo> {
        requireValidBlobPrefix(prefix)
        return io("list \"$prefix\"") {
            requireReachable()
            val directory = if (prefix.isEmpty()) root else entryOrNull(prefix)
            val found = mutableListOf<BlobInfo>()
            if (directory != null && (prefix.isEmpty() || isDirectory(directory))) {
                collectFiles(directory, prefix, found)
            }
            // What was found says nothing if the store went away during the walk.
            requireReachable()
            found.sortedBy { it.path }
        }
    }

    private fun isDirectory(path: Path): Boolean = access.stat(path)?.isDirectory == true

    private fun collectFiles(
        directory: Path,
        directoryPath: String,
        into: MutableList<BlobInfo>,
    ) {
        access.listNames(directory).filter(::isValidBlobSegment).forEach { name ->
            val path = if (directoryPath.isEmpty()) name else "$directoryPath/$name"
            val entry = access.stat(directory / name)
            when {
                entry == null -> Unit // The check at the end of list tells what this means.
                entry.isDirectory -> collectFiles(directory / name, path, into)
                entry.isRegularFile -> into += BlobInfo(path, entry.size)
            }
        }
    }

    override suspend fun read(path: String): ByteArray {
        requireValidBlobPath(path)
        return io("read \"$path\"") {
            requireReachable()
            val file = entryOrNull(path)
            if (file == null || access.stat(file)?.isRegularFile != true) {
                // Not found, unless the store has gone away since the first check.
                requireReachable()
                throw BlobNotFoundException(path)
            }
            fileSystem.read(file) { readByteArray() }
        }
    }

    override suspend fun writeNew(
        path: String,
        bytes: ByteArray,
    ) {
        requireValidBlobPath(path)
        io("write \"$path\"") {
            requireReachable()
            val segments = path.split('/')
            try {
                // A cheap answer for the common case, before writing. Not the real check.
                if (entryOrNull(path) != null) throw BlobAlreadyExistsException(path)
                // The drive may have gone while a large file was being written.
                writer.write(segments, bytes, beforePublishing = ::requireReachable)
            } catch (e: BlobAlreadyExistsException) {
                // This may be the retry of a write that failed to sync. See BlobStore.writeNew.
                writer.syncAbove(segments)
                throw e
            }
        }
    }

    override suspend fun ping(): Reachability = withContext(dispatcher) { reachability() }

    private fun reachability(): Reachability =
        try {
            when {
                access.stat(root / MARKER_NAME) != null -> Reachability.Reachable
                access.stat(root) == null -> missing
                else ->
                    Reachability.Unreachable(
                        UnreachableReason.NOT_INITIALIZED,
                        "$root is not set up as a store, or its drive is not mounted",
                    )
            }
        } catch (e: IOException) {
            Reachability.Unreachable(UnreachableReason.FAILED, "$root: ${e.message}")
        }

    private fun requireReachable() {
        val reachability = reachability()
        if (reachability is Reachability.Unreachable) {
            throw BackendUnavailableException(reachability)
        }
    }

    /**
     * Returns the entry at [path], or null if there is none. Going down one segment at a time means
     * that a file above the entry reads as "not there" on every platform, where the system calls
     * disagree (`ENOENT` or `ENOTDIR`), and that a symbolic link is seen and not followed.
     */
    private fun entryOrNull(path: String): Path? {
        val segments = path.split('/')
        val parent =
            segments.dropLast(1).fold(root as Path?) { directory, segment ->
                directory?.div(segment)?.takeIf(::isDirectory)
            }
        return parent?.div(segments.last())?.takeIf { access.stat(it) != null }
    }

    private suspend fun <T> io(
        action: String,
        checkStoreOnFailure: Boolean = true,
        block: () -> T,
    ): T =
        try {
            withContext(dispatcher) { block() }
        } catch (e: BlobStoreException) {
            throw e
        } catch (e: IOException) {
            // An error from a store that has gone away is that, not a fault of this one call.
            if (checkStoreOnFailure) withContext(dispatcher) { requireReachable() }
            throw BlobStoreFailureException("Cannot $action in $root: ${e.message}", e)
        }
}
