@file:OptIn(ExperimentalForeignApi::class)

package io.github.sameerkrmishra.jotter.backend.localfolder

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import okio.FileSystem
import okio.IOException
import okio.Path
import platform.posix.EEXIST
import platform.posix.EINTR
import platform.posix.F_LOCK
import platform.posix.O_CREAT
import platform.posix.O_DIRECTORY
import platform.posix.O_RDONLY
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.fsync
import platform.posix.link
import platform.posix.lockf
import platform.posix.open
import platform.posix.strerror

private const val LOCK_FILE_MODE = 0x1B6 // rw-rw-rw-, before the umask.

internal actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

internal actual val systemFileSystem: FileSystem = FileSystem.SYSTEM

internal actual fun systemHardLink(
    source: Path,
    target: Path,
): LinkResult =
    when {
        link(source.toString(), target.toString()) == 0 -> LinkResult.LINKED
        errno == EEXIST -> LinkResult.TARGET_EXISTS
        else -> LinkResult.NOT_LINKED
    }

// lockf is fcntl(F_SETLKW) from offset 0 to the end of any file size, the range and the lock family
// of FileChannel.lock() on the JVM. flock() would not conflict with that lock.
internal actual fun <T> withProcessFileLock(
    file: Path,
    block: () -> T,
): T {
    val descriptor = open(file.toString(), O_CREAT or O_WRONLY, LOCK_FILE_MODE)
    if (descriptor < 0) throw IOException("Cannot open $file: ${lastError()}")
    try {
        while (lockf(descriptor, F_LOCK, 0) != 0) {
            if (errno != EINTR) throw IOException("Cannot lock $file: ${lastError()}")
        }
        return block()
    } finally {
        close(descriptor)
    }
}

internal actual fun syncDirectoryBestEffort(directory: Path) {
    val descriptor = open(directory.toString(), O_RDONLY or O_DIRECTORY)
    if (descriptor >= 0) {
        fsync(descriptor)
        close(descriptor)
    }
}

private fun lastError(): String = strerror(errno)?.toKString().orEmpty()
