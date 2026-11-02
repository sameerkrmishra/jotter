package io.github.sameerkrmishra.jotter.backend.localfolder

import kotlinx.coroutines.CoroutineDispatcher
import okio.FileSystem
import okio.IOException
import okio.Path

/**
 * What is at a path, without following a symbolic link: a link is neither a directory nor a regular
 * file.
 *
 * @property isDirectory Whether it is a directory.
 * @property isRegularFile Whether it is a regular file.
 * @property size Its length in bytes.
 */
internal data class FolderEntry(
    val isDirectory: Boolean,
    val isRegularFile: Boolean,
    val size: Long,
)

/** The dispatcher for blocking file I/O. */
internal expect val ioDispatcher: CoroutineDispatcher

/** The real file system. */
internal expect val systemFileSystem: FileSystem

/**
 * Returns what is at [path], or null only if the system says that nothing is. okio's
 * `metadataOrNull` also returns null for any other error on the JVM and Android, which would make
 * an unreadable or failing folder look empty.
 *
 * @throws IOException for every other error, such as `EACCES` or `EIO`.
 */
internal expect fun strictStat(path: Path): FolderEntry?

/**
 * Returns the names in [directory], without `.` and `..`. okio's `list` stops silently at a read
 * error and returns what it has so far.
 *
 * @throws IOException if opening or reading the directory fails at any point.
 */
internal expect fun strictListNames(directory: Path): List<String>

/** Makes [target] a hard link to [source], atomically and without replacing anything. */
internal expect fun systemHardLink(
    source: Path,
    target: Path,
): LinkResult

/**
 * Runs [block] while holding an exclusive POSIX record lock (`fcntl`) on [file], creating the file
 * if needed and waiting for the lock. The kernel drops the lock when the process dies. If the lock
 * cannot be taken, [block] runs anyway, without it, and gets the reason. Otherwise it gets null.
 *
 * This locks against other processes only, and closing the file drops the lock even if another
 * thread of this process holds it, so callers must never run two of these at once.
 */
internal expect fun withProcessFileLock(
    file: Path,
    block: (lockFailure: IOException?) -> Unit,
)

/**
 * Makes [directory]'s entries durable. Returns normally if the file system says it has no such
 * operation, as some do for directories, since nothing more can be done there.
 *
 * @throws IOException if the file system tried and failed, such as with `EIO`.
 */
internal expect fun syncDirectory(directory: Path)

/** The file operations that tests replace to inject faults okio's file system cannot show. */
internal data class FolderAccess(
    val stat: (Path) -> FolderEntry? = ::strictStat,
    val listNames: (Path) -> List<String> = ::strictListNames,
    val hardLink: (source: Path, target: Path) -> LinkResult = ::systemHardLink,
    val lock: (file: Path, block: (IOException?) -> Unit) -> Unit = ::withProcessFileLock,
    val syncDirectory: (Path) -> Unit = ::syncDirectory,
)
