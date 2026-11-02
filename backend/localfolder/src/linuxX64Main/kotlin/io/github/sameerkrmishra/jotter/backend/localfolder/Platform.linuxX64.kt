@file:OptIn(ExperimentalForeignApi::class)

package io.github.sameerkrmishra.jotter.backend.localfolder

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import okio.FileSystem
import okio.IOException
import okio.Path
import platform.posix.EACCES
import platform.posix.EEXIST
import platform.posix.EINTR
import platform.posix.EINVAL
import platform.posix.ENOENT
import platform.posix.ENOSYS
import platform.posix.ENOTSUP
import platform.posix.EOPNOTSUPP
import platform.posix.EPERM
import platform.posix.F_LOCK
import platform.posix.O_CREAT
import platform.posix.O_DIRECTORY
import platform.posix.O_RDONLY
import platform.posix.O_WRONLY
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.close
import platform.posix.closedir
import platform.posix.errno
import platform.posix.fsync
import platform.posix.link
import platform.posix.lockf
import platform.posix.lstat
import platform.posix.open
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.set_posix_errno
import platform.posix.stat
import platform.posix.strerror

private const val LOCK_FILE_MODE = 0x1B6 // rw-rw-rw-, before the umask.

internal actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

internal actual val systemFileSystem: FileSystem = FileSystem.SYSTEM

internal actual fun strictStat(path: Path): FolderEntry? =
    memScoped {
        val status = alloc<stat>()
        when {
            lstat(path.toString(), status.ptr) == 0 -> {
                val type = status.st_mode.toInt() and S_IFMT
                FolderEntry(type == S_IFDIR, type == S_IFREG, status.st_size)
            }
            errno == ENOENT -> null
            else -> throw IOException("Cannot read $path: ${lastError()}")
        }
    }

internal actual fun strictListNames(directory: Path): List<String> {
    val stream =
        opendir(directory.toString())
            ?: throw IOException("Cannot open $directory: ${lastError()}")
    try {
        val names = mutableListOf<String>()
        while (true) {
            // readdir returns null at the end as well as on an error. Only errno tells them apart.
            set_posix_errno(0)
            val entry = readdir(stream)
            if (entry == null) {
                if (errno != 0) throw IOException("Cannot read $directory: ${lastError()}")
                return names
            }
            val name = entry.pointed.d_name.toKString()
            if (name != "." && name != "..") names += name
        }
    } finally {
        closedir(stream)
    }
}

internal actual fun systemHardLink(
    source: Path,
    target: Path,
): LinkResult =
    when {
        link(source.toString(), target.toString()) == 0 -> LinkResult.LINKED
        errno == EEXIST -> LinkResult.TARGET_EXISTS
        isUnsupportedLinkError(errno) -> LinkResult.NOT_SUPPORTED
        else -> throw IOException("Cannot link $target: ${lastError()}")
    }

/** Whether [error] means that hard links do not exist here, not that this one link failed. */
internal fun isUnsupportedLinkError(error: Int): Boolean =
    error == EPERM ||
        error == EACCES ||
        error == ENOTSUP ||
        error == EOPNOTSUPP ||
        error == ENOSYS

// lockf is fcntl(F_SETLKW) from offset 0 to the end of any file size, the range and the lock family
// of FileChannel.lock() on the JVM. flock() would not conflict with that lock.
internal actual fun withProcessFileLock(
    file: Path,
    block: (lockFailure: IOException?) -> Unit,
) {
    val descriptor = open(file.toString(), O_CREAT or O_WRONLY, LOCK_FILE_MODE)
    if (descriptor < 0) {
        block(IOException("Cannot open $file: ${lastError()}"))
        return
    }
    try {
        var result = lockf(descriptor, F_LOCK, 0)
        while (result != 0 && errno == EINTR) result = lockf(descriptor, F_LOCK, 0)
        block(if (result == 0) null else IOException("Cannot lock $file: ${lastError()}"))
    } finally {
        close(descriptor)
    }
}

internal actual fun syncDirectory(directory: Path) {
    val descriptor = open(directory.toString(), O_RDONLY or O_DIRECTORY)
    if (descriptor < 0) throw IOException("Cannot open $directory: ${lastError()}")
    try {
        if (fsync(descriptor) != 0 && !isUnsupportedSyncError(errno)) {
            throw IOException("Cannot sync $directory: ${lastError()}")
        }
    } finally {
        close(descriptor)
    }
}

/** Whether [error] means that this file system cannot sync a directory, not that it failed. */
internal fun isUnsupportedSyncError(error: Int): Boolean =
    error == EINVAL || error == ENOTSUP || error == EOPNOTSUPP || error == ENOSYS

private fun lastError(): String = strerror(errno)?.toKString().orEmpty()
