package io.github.sameerkrmishra.jotter.core.logging

import okio.FileSystem
import okio.IOException
import okio.Path

/** What a debug log takes of each app's log: the newest 128 KiB. */
public const val DEFAULT_RECENT_BYTES: Int = 128 * 1024

private val LOG_NAME = Regex("""([A-Za-z0-9_-]+)(?:\.(\d{1,3}))?\.log""")

/**
 * Reads the logs that [RotatingFileSink]s wrote to [directory], for a debug log that the user
 * exports. Reading never changes them.
 *
 * @param directory Where the logs are.
 * @param fileSystem The file system, the real one outside tests.
 */
public class LogFiles(
    private val directory: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    /**
     * The names of the logs there, such as `cli` and `desktop`, sorted. None if the directory does
     * not exist or cannot be read.
     */
    public fun names(): List<String> = files().mapNotNull(::logName).distinct().sorted()

    /**
     * The newest [maxBytes] of the log [name], across its rotated files, oldest line first. A line
     * cut at the start is left out. Empty if there is no such log; a file that cannot be read is
     * left out as if it were not there.
     */
    public fun recent(
        name: String,
        maxBytes: Int = DEFAULT_RECENT_BYTES,
    ): String {
        val parts =
            files()
                .filter { logName(it) == name }
                .sortedByDescending(::rotation)
                .map(::read)
        val all = parts.fold(ByteArray(0)) { joined, part -> joined + part }
        if (all.size <= maxBytes) return all.decodeToString()
        val tail = all.copyOfRange(all.size - maxBytes, all.size)
        val firstWhole = tail.indexOf('\n'.code.toByte()) + 1
        return tail.copyOfRange(firstWhole, tail.size).decodeToString()
    }

    private fun logName(path: Path): String? = LOG_NAME.matchEntire(path.name)?.groupValues?.get(1)

    /** 0 for the current file, higher for older ones. */
    private fun rotation(path: Path): Int =
        LOG_NAME
            .matchEntire(path.name)
            ?.groupValues
            ?.get(2)
            ?.toIntOrNull() ?: 0

    @Suppress("SwallowedException") // A log that cannot be read is simply not in the debug log.
    private fun files(): List<Path> =
        try {
            fileSystem.listOrNull(directory).orEmpty()
        } catch (_: IOException) {
            emptyList()
        }

    @Suppress("SwallowedException") // Rotated away between listing and reading, or unreadable.
    private fun read(path: Path): ByteArray =
        try {
            fileSystem.read(path) { readByteArray() }
        } catch (_: IOException) {
            ByteArray(0)
        }
}
