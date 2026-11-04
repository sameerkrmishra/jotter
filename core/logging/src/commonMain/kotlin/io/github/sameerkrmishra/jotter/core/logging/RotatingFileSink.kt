package io.github.sameerkrmishra.jotter.core.logging

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import okio.BufferedSink
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.buffer

/** A log file is rotated once it reaches this size. */
public const val DEFAULT_MAX_FILE_BYTES: Long = 256L * 1024

/** The current file and the older ones kept: at most 1 MiB with [DEFAULT_MAX_FILE_BYTES]. */
public const val DEFAULT_FILES: Int = 4

/**
 * Writes log lines to `[name].log` in [directory], creating the directory when the first line is
 * written. Once the file reaches [maxFileBytes], it becomes `[name].1.log`, the one before
 * `[name].2.log` and so on, and the oldest of [files] is deleted, so the log never holds much more
 * than [files] times [maxFileBytes] bytes.
 *
 * Each line is flushed as it is written, so a crash loses none of what came before. One process
 * writes one [name]; the apps use their own names, so that the desktop app and the CLI never
 * rotate each other's files.
 *
 * @param directory Where the files are.
 * @param name The files' base name, such as `desktop`.
 * @param maxFileBytes The size at which a file is rotated.
 * @param files How many files are kept, the current one included. At least 1.
 * @param fileSystem The file system, the real one outside tests.
 */
public class RotatingFileSink(
    private val directory: Path,
    private val name: String,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val files: Int = DEFAULT_FILES,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : LogSink,
    AutoCloseable {
    private val lock = reentrantLock()
    private var out: BufferedSink? = null
    private var size = 0L

    init {
        require(files >= 1) { "At least the current file is kept" }
        require(maxFileBytes > 0) { "A file must hold something" }
    }

    /** Appends [record]. Throws [IOException] if it cannot, and opens the file again next time. */
    override fun write(record: LogRecord) {
        val bytes = record.format().encodeToByteArray()
        lock.withLock {
            try {
                val sink = out ?: open()
                sink.write(bytes)
                sink.flush()
                size += bytes.size
                if (size >= maxFileBytes) rotate()
            } catch (e: IOException) {
                closeFile()
                throw e
            }
        }
    }

    /** Closes the current file. A later [write] opens it again. */
    override fun close() {
        lock.withLock { closeFile() }
    }

    private fun open(): BufferedSink {
        fileSystem.createDirectories(directory)
        val file = logFile(directory, name, 0)
        size = fileSystem.metadataOrNull(file)?.size ?: 0
        return fileSystem.appendingSink(file).buffer().also { out = it }
    }

    private fun rotate() {
        closeFile()
        fileSystem.delete(logFile(directory, name, files - 1), mustExist = false)
        for (index in files - 2 downTo 0) {
            val from = logFile(directory, name, index)
            val to = logFile(directory, name, index + 1)
            if (fileSystem.exists(from)) fileSystem.atomicMove(from, to)
        }
        size = 0
    }

    @Suppress("SwallowedException") // A file that cannot even close has nothing more to give.
    private fun closeFile() {
        try {
            out?.close()
        } catch (_: IOException) {
        }
        out = null
    }
}

/** `[name].log` for [index] 0, the current file, and `[name].[index].log` for older ones. */
internal fun logFile(
    directory: Path,
    name: String,
    index: Int,
): Path = if (index == 0) directory / "$name.log" else directory / "$name.$index.log"
