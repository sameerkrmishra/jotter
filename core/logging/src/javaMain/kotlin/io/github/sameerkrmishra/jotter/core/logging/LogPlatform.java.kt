package io.github.sameerkrmishra.jotter.core.logging

import okio.Path
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.Path as JavaPath

private val DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------")
private val FILE_MODE = PosixFilePermissions.fromString("rw-------")

internal actual fun createPrivateDirectory(directory: Path) {
    val path = Paths.get(directory.toString())
    path.parent?.let(Files::createDirectories)
    if (!Files.isDirectory(path)) {
        try {
            Files.createDirectory(path, mode(DIRECTORY_MODE))
        } catch (_: UnsupportedOperationException) {
            // A file system without POSIX modes.
            Files.createDirectory(path)
        }
    }
    tighten(path, DIRECTORY_MODE)
}

internal actual fun appendPrivate(
    file: Path,
    bytes: ByteArray,
) {
    val path = Paths.get(file.toString())
    val options = setOf(CREATE, WRITE, APPEND)
    val channel =
        try {
            Files.newByteChannel(path, options, mode(FILE_MODE))
        } catch (_: UnsupportedOperationException) {
            Files.newByteChannel(path, options)
        }
    channel.use {
        tighten(path, FILE_MODE)
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) it.write(buffer)
    }
}

internal actual fun <T> withProcessLock(
    lockFile: Path,
    block: () -> T,
): T {
    val path = Paths.get(lockFile.toString())
    val options = setOf(CREATE, WRITE)
    val channel =
        try {
            FileChannel.open(path, options, mode(FILE_MODE))
        } catch (_: UnsupportedOperationException) {
            FileChannel.open(path, options)
        }
    return channel.use {
        tighten(path, FILE_MODE)
        it.lock().use { block() }
    }
}

internal actual val processId: Long by lazy {
    // Not ProcessHandle, which Android does not have. /proc/self names this process on both.
    try {
        Files.readSymbolicLink(Paths.get("/proc/self")).toString().toLong()
    } catch (_: IOException) {
        0L
    } catch (_: NumberFormatException) {
        0L
    } catch (_: UnsupportedOperationException) {
        0L
    }
}

private fun mode(permissions: Set<PosixFilePermission>): FileAttribute<Set<PosixFilePermission>> =
    PosixFilePermissions.asFileAttribute(permissions)

/** Narrows [path] to [permissions] if it allows more, when this user may; never widens it. */
@Suppress("SwallowedException") // Not ours, or no modes: what it allows is not ours to change.
private fun tighten(
    path: JavaPath,
    permissions: Set<PosixFilePermission>,
) {
    try {
        if (!permissions.containsAll(Files.getPosixFilePermissions(path))) {
            Files.setPosixFilePermissions(path, permissions)
        }
    } catch (_: IOException) {
    } catch (_: UnsupportedOperationException) {
    }
}
