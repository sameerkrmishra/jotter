package io.github.sameerkrmishra.jotter.core.logging

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import okio.FileSystem
import okio.IOException
import okio.Path

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
 * Several processes may write one [name], as CLI commands that run at once do (ADR-0021). Each line
 * is appended in one write, under an exclusive lock on `[name].lock` that every process takes,
 * which also covers the size check and the rotation, so no line is cut or lost. Each line carries
 * the process's id. The directory is readable by this user alone (0700), the files too (0600), and
 * existing ones of this user's are narrowed to that.
 *
 * Each line is in the file once [write] returns, so a crash loses none of what came before.
 *
 * @param directory Where the files are, on the real file system.
 * @param name The files' base name, such as `desktop`.
 * @param maxFileBytes The size at which a file is rotated.
 * @param files How many files are kept, the current one included. At least 1.
 */
public class RotatingFileSink(
    private val directory: Path,
    private val name: String,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val files: Int = DEFAULT_FILES,
) : LogSink,
    AutoCloseable {
    private val fileSystem = FileSystem.SYSTEM

    init {
        require(files >= 1) { "At least the current file is kept" }
        require(maxFileBytes > 0) { "A file must hold something" }
    }

    /** Appends [record]. Throws [IOException] if it cannot; the next line tries again. */
    override fun write(record: LogRecord) {
        val bytes = record.format(processId).encodeToByteArray()
        // One thread at a time in this process; the file lock keeps other processes out.
        processLock.withLock {
            createPrivateDirectory(directory)
            withProcessLock(directory / "$name.lock") {
                val current = logFile(directory, name, 0)
                appendPrivate(current, bytes)
                if ((fileSystem.metadataOrNull(current)?.size ?: 0) >= maxFileBytes) rotate()
            }
        }
    }

    /** Holds nothing open between lines, so there is nothing to close. */
    override fun close() {
        // Each line opens and closes the file, as another process may have rotated it.
    }

    private fun rotate() {
        fileSystem.delete(logFile(directory, name, files - 1), mustExist = false)
        for (index in files - 2 downTo 0) {
            val from = logFile(directory, name, index)
            val to = logFile(directory, name, index + 1)
            if (fileSystem.exists(from)) fileSystem.atomicMove(from, to)
        }
    }
}

/** One writer at a time in this process, whatever the sink: file locks are per process. */
private val processLock = reentrantLock()

/** `[name].log` for [index] 0, the current file, and `[name].[index].log` for older ones. */
internal fun logFile(
    directory: Path,
    name: String,
    index: Int,
): Path = if (index == 0) directory / "$name.log" else directory / "$name.$index.log"
