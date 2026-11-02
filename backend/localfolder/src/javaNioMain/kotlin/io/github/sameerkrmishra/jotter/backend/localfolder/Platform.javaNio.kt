package io.github.sameerkrmishra.jotter.backend.localfolder

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okio.FileSystem
import okio.Path
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption

@Suppress("InjectDispatcher") // This is the default that the store gets injected.
internal actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

internal actual val systemFileSystem: FileSystem = FileSystem.SYSTEM

internal actual fun systemHardLink(
    source: Path,
    target: Path,
): LinkResult =
    try {
        Files.createLink(target.toNioPath(), source.toNioPath())
        LinkResult.LINKED
    } catch (_: FileAlreadyExistsException) {
        LinkResult.TARGET_EXISTS
    } catch (_: IOException) {
        // The JDK reports EPERM and the like as a FileSystemException with no errno to look at.
        LinkResult.NOT_LINKED
    }

// FileChannel.lock() is fcntl(F_SETLKW) over the whole file, which is what the native actual uses.
internal actual fun <T> withProcessFileLock(
    file: Path,
    block: () -> T,
): T =
    FileChannel
        .open(file.toNioPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        .use { channel -> channel.lock().use { block() } }

internal actual fun syncDirectoryBestEffort(directory: Path) {
    try {
        FileChannel.open(directory.toNioPath(), StandardOpenOption.READ).use { it.force(true) }
    } catch (_: IOException) {
        // Not every file system can do this. The file itself is complete on disk either way.
    }
}
