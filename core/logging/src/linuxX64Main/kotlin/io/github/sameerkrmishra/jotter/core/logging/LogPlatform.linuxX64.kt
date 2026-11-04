@file:OptIn(ExperimentalForeignApi::class)

package io.github.sameerkrmishra.jotter.core.logging

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import okio.FileSystem
import okio.IOException
import okio.Path
import platform.posix.EEXIST
import platform.posix.EINTR
import platform.posix.F_LOCK
import platform.posix.O_APPEND
import platform.posix.O_CLOEXEC
import platform.posix.O_CREAT
import platform.posix.O_WRONLY
import platform.posix.chmod
import platform.posix.close
import platform.posix.errno
import platform.posix.fchmod
import platform.posix.getpid
import platform.posix.lockf
import platform.posix.mkdir
import platform.posix.open
import platform.posix.strerror
import platform.posix.write

private const val DIRECTORY_MODE = 0x1C0u // rwx------
private const val FILE_MODE = 0x180u // rw-------

internal actual fun createPrivateDirectory(directory: Path) {
    directory.parent?.let(FileSystem.SYSTEM::createDirectories)
    if (mkdir(directory.toString(), DIRECTORY_MODE) != 0 && errno != EEXIST) {
        throw IOException("Cannot create $directory: ${lastError()}")
    }
    if (FileSystem.SYSTEM.metadataOrNull(directory)?.isDirectory != true) {
        throw IOException("$directory is not a directory")
    }
    // Fails, and changes nothing, unless this user owns it.
    chmod(directory.toString(), DIRECTORY_MODE)
}

internal actual fun appendPrivate(
    file: Path,
    bytes: ByteArray,
) {
    val descriptor = open(file.toString(), O_WRONLY or O_APPEND or O_CREAT or O_CLOEXEC, FILE_MODE)
    if (descriptor < 0) throw IOException("Cannot open $file: ${lastError()}")
    try {
        fchmod(descriptor, FILE_MODE)
        var offset = 0
        bytes.usePinned { pinned ->
            while (offset < bytes.size) {
                val written =
                    write(descriptor, pinned.addressOf(offset), (bytes.size - offset).toULong())
                if (written < 0 &&
                    errno != EINTR
                ) {
                    throw IOException("Cannot write $file: ${lastError()}")
                }
                if (written > 0) offset += written.toInt()
            }
        }
    } finally {
        close(descriptor)
    }
}

internal actual fun <T> withProcessLock(
    lockFile: Path,
    block: () -> T,
): T {
    val descriptor = open(lockFile.toString(), O_WRONLY or O_CREAT or O_CLOEXEC, FILE_MODE)
    if (descriptor < 0) throw IOException("Cannot open $lockFile: ${lastError()}")
    try {
        fchmod(descriptor, FILE_MODE)
        // lockf is fcntl(F_SETLKW) over the whole file, as FileChannel.lock() is on the JVM.
        var result = lockf(descriptor, F_LOCK, 0)
        while (result != 0 && errno == EINTR) result = lockf(descriptor, F_LOCK, 0)
        if (result != 0) throw IOException("Cannot lock $lockFile: ${lastError()}")
        return block()
    } finally {
        // Closing releases the lock.
        close(descriptor)
    }
}

internal actual val processId: Long = getpid().toLong()

private fun lastError(): String = strerror(errno)?.toKString() ?: "error $errno"
