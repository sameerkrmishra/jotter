package io.github.sameerkrmishra.jotter.backend.localfolder

import kotlinx.coroutines.CoroutineDispatcher
import okio.FileSystem
import okio.Path

/** The dispatcher for blocking file I/O. */
internal expect val ioDispatcher: CoroutineDispatcher

/** The real file system. */
internal expect val systemFileSystem: FileSystem

/** Makes [target] a hard link to [source], atomically and without replacing anything. */
internal expect fun systemHardLink(
    source: Path,
    target: Path,
): LinkResult

/**
 * Runs [block] while holding an exclusive POSIX record lock (`fcntl`) on [file], creating the file
 * if needed and waiting for the lock. The kernel drops the lock when the process dies.
 *
 * This locks against other processes only, and closing the file drops the lock even if another
 * thread of this process holds it, so callers must never run two of these at once.
 *
 * @throws okio.IOException if the file system fails or cannot lock.
 */
internal expect fun <T> withProcessFileLock(
    file: Path,
    block: () -> T,
): T

/** Asks the file system to make [directory]'s entries durable, where it is able to. */
internal expect fun syncDirectoryBestEffort(directory: Path)
