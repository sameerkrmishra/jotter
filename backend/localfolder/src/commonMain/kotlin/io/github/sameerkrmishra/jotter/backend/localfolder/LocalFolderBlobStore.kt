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
 */
public class LocalFolderBlobStore internal constructor(
    private val root: Path,
    private val fileSystem: FileSystem,
    hardLink: (source: Path, target: Path) -> LinkResult,
    private val dispatcher: CoroutineDispatcher,
) : BlobStore {
    /** Creates a store over the directory [root]. This touches nothing on disk. */
    public constructor(root: Path) : this(root, systemFileSystem, ::systemHardLink, ioDispatcher)

    private val writer = NoReplaceWriter(root, fileSystem, hardLink)
    private val missing = Reachability.Unreachable(UnreachableReason.MISSING, "$root is missing")

    /**
     * Sets [root], which must exist, up as a store. Doing so again is harmless. Call this once,
     * when the user adds the folder as a backend. Never call it because [ping] said
     * [UnreachableReason.NOT_INITIALIZED] during sync, or a bare mount point becomes a store.
     *
     * @throws BackendUnavailableException if [root] does not exist.
     * @throws BlobStoreFailureException if the file system fails.
     */
    public suspend fun initialize(): Unit =
        io("initialize") {
            if (fileSystem.metadataOrNull(root) == null) throw BackendUnavailableException(missing)
            if (!fileSystem.exists(root / MARKER_NAME)) {
                // Not mustCreate: a concurrent initialize has the same aim.
                fileSystem.write(root / MARKER_NAME, mustCreate = false) { }
                syncDirectoryBestEffort(root)
            }
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
            found.sortedBy { it.path }
        }
    }

    private fun isDirectory(path: Path): Boolean = fileSystem.metadata(path).isDirectory

    private fun collectFiles(
        directory: Path,
        directoryPath: String,
        into: MutableList<BlobInfo>,
    ) {
        fileSystem.list(directory).filter { isValidBlobSegment(it.name) }.forEach { child ->
            val path = if (directoryPath.isEmpty()) child.name else "$directoryPath/${child.name}"
            val metadata = fileSystem.metadata(child)
            when {
                metadata.isDirectory -> collectFiles(child, path, into)
                metadata.isRegularFile -> into += BlobInfo(path, metadata.size ?: 0L)
            }
        }
    }

    override suspend fun read(path: String): ByteArray {
        requireValidBlobPath(path)
        return io("read \"$path\"") {
            requireReachable()
            val file = entryOrNull(path)
            if (file == null || !fileSystem.metadata(file).isRegularFile) {
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
            // A cheap answer for the common case, before writing anything. Not the real check.
            if (entryOrNull(path) != null) throw BlobAlreadyExistsException(path)
            // The drive may have gone while a large file was being written.
            writer.write(path.split('/'), bytes, beforePublishing = ::requireReachable)
        }
    }

    override suspend fun ping(): Reachability =
        try {
            withContext(dispatcher) { reachability() }
        } catch (e: IOException) {
            Reachability.Unreachable(UnreachableReason.FAILED, "$root: ${e.message}")
        }

    private fun reachability(): Reachability =
        when {
            fileSystem.exists(root / MARKER_NAME) -> Reachability.Reachable
            fileSystem.metadataOrNull(root) == null -> missing
            else ->
                Reachability.Unreachable(
                    UnreachableReason.NOT_INITIALIZED,
                    "$root is not set up as a store, or its drive is not mounted",
                )
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
                directory?.div(segment)?.takeIf {
                    fileSystem.metadataOrNull(it)?.isDirectory == true
                }
            }
        return parent?.div(segments.last())?.takeIf { fileSystem.metadataOrNull(it) != null }
    }

    private suspend fun <T> io(
        action: String,
        block: () -> T,
    ): T =
        try {
            withContext(dispatcher) { block() }
        } catch (e: BlobStoreException) {
            throw e
        } catch (e: IOException) {
            throw BlobStoreFailureException("Cannot $action in $root: ${e.message}", e)
        }
}
