package io.github.sameerkrmishra.jotter.core.logging

import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class LogFileTest {
    private val fs = FileSystem.SYSTEM
    private val root: Path =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "jotter-logging-${Random.nextLong().toULong()}"
    private val logs = root / "logs"

    @AfterTest
    fun cleanUp() {
        fs.deleteRecursively(root)
    }

    private fun record(message: String) =
        LogRecord(Instant.parse("2026-11-04T10:00:00Z"), LogLevel.INFO, "t", message, null)

    /** Each line is 32 bytes: the time, the level, the tag and a message of 3 digits. */
    private fun line(number: Int) = record(number.toString().padStart(3, '0'))

    private fun sink(
        lines: Int,
        files: Int,
    ) = RotatingFileSink(logs, "app", line(0).format(processId).length.toLong() * lines, files)

    private fun fileNames() =
        fs
            .list(logs)
            .map { it.name }
            .filter { it.endsWith(".log") }
            .sorted()

    @Test
    fun theFirstLineCreatesTheDirectoryAndFile() {
        RotatingFileSink(logs, "desktop").use { it.write(record("hello")) }
        assertEquals(
            "2026-11-04T10:00:00Z $processId INFO t: hello\n",
            fs.read(logs / "desktop.log") { readUtf8() },
        )
    }

    @Test
    fun linesAreAppendedAcrossSinks() {
        RotatingFileSink(logs, "cli").use { it.write(record("one")) }
        RotatingFileSink(logs, "cli").use { it.write(record("two")) }
        assertEquals(2, fs.read(logs / "cli.log") { readUtf8() }.lines().count { it.isNotEmpty() })
    }

    @Test
    fun fullFilesRotateAndTheOldestGoes() {
        sink(lines = 2, files = 3).use { sink -> repeat(10) { sink.write(line(it)) } }
        assertEquals(listOf("app.1.log", "app.2.log"), fileNames())
        val reader = LogFiles(logs, fs)
        // 10 lines, 2 a file: the current file is empty, the two kept hold the last four.
        assertEquals(listOf("006", "007", "008", "009"), reader.recent("app").messages())
    }

    @Test
    fun aSizeAboveTheLimitRotatesWithTheNextLine() {
        sink(lines = 3, files = 2).use { sink ->
            sink.write(line(1))
            sink.write(line(2))
        }
        sink(lines = 3, files = 2).use { sink ->
            sink.write(line(3))
            sink.write(line(4))
        }
        assertEquals(listOf("app.1.log", "app.log"), fileNames())
        assertEquals(listOf("004"), fs.read(logs / "app.log") { readUtf8() }.messages())
    }

    @Test
    fun oneFileKeptIsEmptiedWhenFull() {
        RotatingFileSink(logs, "app", maxFileBytes = 1, files = 1).use {
            it.write(line(1))
        }
        assertEquals(emptyList(), fileNames())
    }

    @Test
    fun aDirectoryThatCannotBeMadeFailsTheWriteAndTheNextOneTriesAgain() {
        fs.createDirectories(root)
        fs.write(logs) { writeUtf8("a file, not a directory") }
        RotatingFileSink(logs, "app").use { sink ->
            assertFailsWith<IOException> { sink.write(record("lost")) }
            fs.delete(logs)
            sink.write(record("kept"))
        }
        assertEquals(listOf("kept"), LogFiles(logs, fs).recent("app").messages())
    }

    @Test
    fun theLimitsMustMakeSense() {
        assertFailsWith<IllegalArgumentException> { RotatingFileSink(logs, "a", files = 0) }
        assertFailsWith<IllegalArgumentException> { RotatingFileSink(logs, "a", maxFileBytes = 0) }
    }

    @Test
    fun logsAreFoundByName() {
        fs.createDirectories(logs)
        listOf("desktop.log", "desktop.1.log", "cli.log", "notes.txt", "x.y.log").forEach {
            fs.write(logs / it) { writeUtf8("") }
        }
        assertEquals(listOf("cli", "desktop"), LogFiles(logs, fs).names())
    }

    @Test
    fun noDirectoryMeansNoLogs() {
        val reader = LogFiles(root / "missing", fs)
        assertEquals(emptyList(), reader.names())
        assertEquals("", reader.recent("desktop"))
    }

    @Test
    fun recentStartsAtAWholeLine() {
        RotatingFileSink(logs, "app").use { sink ->
            repeat(5) { sink.write(line(it)) }
        }
        val size = line(0).format(processId).length
        val recent = LogFiles(logs, fs).recent("app", maxBytes = size * 2 + 5)
        assertEquals(listOf("003", "004"), recent.messages())
        assertTrue(recent.startsWith("2026"))
    }

    private fun String.messages() =
        lines().filter { it.isNotEmpty() }.map { it.substringAfter("t: ") }
}
