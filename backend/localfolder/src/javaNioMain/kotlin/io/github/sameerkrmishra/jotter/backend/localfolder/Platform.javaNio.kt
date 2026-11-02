package io.github.sameerkrmishra.jotter.backend.localfolder

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okio.FileSystem
import okio.Path
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryIteratorException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.InvalidPathException
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

private val UNSUPPORTED_LINK_REASONS =
    setOf("Operation not permitted", "Operation not supported", "Function not implemented")

private val FILE_STORES_WITHOUT_HARD_LINKS = setOf("vfat", "msdos", "exfat")

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
    } catch (e: InvalidPathException) {
        // A valid blob path that this process's encoding cannot express. Every call stats first.
        throw IOException("Cannot express $path in this locale", e)
    }

internal actual fun strictListNames(directory: Path): List<String> =
    try {
        Files.newDirectoryStream(directory.toNioPath()).use { stream ->
            stream.map { it.fileName.toString() }.filter(::isRepresentable)
        }
    } catch (e: DirectoryIteratorException) {
        // The stream's iterator cannot throw a checked exception, so it wraps it in this one.
        throw e.cause ?: IOException(e)
    }

// A name that this process's encoding cannot express, such as a non-ASCII one under LC_ALL=C, has
// no path that could read it. Like a name with a colon, it is not a blob.
private fun isRepresentable(name: String): Boolean =
    try {
        FileSystems.getDefault().getPath(name)
        true
    } catch (_: InvalidPathException) {
        false
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
    } catch (_: AccessDeniedException) {
        LinkResult.NOT_SUPPORTED // EACCES, which is what a security policy gives.
    } catch (_: UnsupportedOperationException) {
        LinkResult.NOT_SUPPORTED
    } catch (e: FileSystemException) {
        if (!isUnsupportedLinkFailure(e.reason, fileStoreType(target))) throw e
        LinkResult.NOT_SUPPORTED
    }

private fun fileStoreType(path: Path): String =
    try {
        Files.getFileStore(path.parent?.toNioPath()).type()
    } catch (_: IOException) {
        ""
    }

/**
 * Whether a failed link means that hard links do not exist here. The JDK has no exception type for
 * `EPERM`, `ENOTSUP` or `ENOSYS`, and gives no number, so this matches their exact text, which is
 * English unless the system translates it. The type of the file store covers that case for the
 * common drives without hard links. Anything else counts as a real failure: that can only make a
 * write fail, never replace a file.
 */
internal fun isUnsupportedLinkFailure(
    reason: String?,
    fileStoreType: String,
): Boolean = reason in UNSUPPORTED_LINK_REASONS || fileStoreType in FILE_STORES_WITHOUT_HARD_LINKS

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
