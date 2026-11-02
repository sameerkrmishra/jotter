package io.github.sameerkrmishra.jotter.backend.localfolder

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okio.FileSystem
import okio.Path
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.DirectoryIteratorException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * How the JDK words the `errno` values of "no such operation here". It gives no number to look at.
 * The text can be translated, and then the failure counts as real: a loud error, never a lost one.
 */
private val UNSUPPORTED_SYNC_MESSAGES =
    listOf(
        "Invalid argument",
        "Operation not supported",
        "Function not implemented",
        "Is a directory",
    )

@Suppress("InjectDispatcher") // This is the default that the store gets injected.
internal actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

internal actual val systemFileSystem: FileSystem = FileSystem.SYSTEM

internal actual fun strictStat(path: Path): FolderEntry? =
    try {
        val attributes =
            Files.readAttributes(
                path.toNioPath(),
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        FolderEntry(attributes.isDirectory, attributes.isRegularFile, attributes.size())
    } catch (_: NoSuchFileException) {
        null
    }

internal actual fun strictListNames(directory: Path): List<String> =
    try {
        Files.newDirectoryStream(directory.toNioPath()).use { stream ->
            stream.map { it.fileName.toString() }
        }
    } catch (e: DirectoryIteratorException) {
        // The stream's iterator cannot throw a checked exception, so it wraps it in this one.
        throw e.cause ?: IOException(e)
    }

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
internal actual fun withProcessFileLock(
    file: Path,
    block: (lockFailure: IOException?) -> Unit,
) {
    val channel =
        try {
            FileChannel.open(file.toNioPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        } catch (e: IOException) {
            block(e)
            return
        }
    channel.use {
        val lock =
            try {
                it.lock()
            } catch (e: IOException) {
                block(e)
                return
            }
        lock.use { block(null) }
    }
}

internal actual fun syncDirectory(directory: Path) {
    try {
        FileChannel.open(directory.toNioPath(), StandardOpenOption.READ).use { it.force(true) }
    } catch (e: IOException) {
        if (!isUnsupportedSyncFailure(e)) throw e
    }
}

/** Whether [failure] means that this file system cannot sync a directory, not that it failed. */
internal fun isUnsupportedSyncFailure(failure: IOException): Boolean =
    UNSUPPORTED_SYNC_MESSAGES.any { failure.message.orEmpty().contains(it) }
