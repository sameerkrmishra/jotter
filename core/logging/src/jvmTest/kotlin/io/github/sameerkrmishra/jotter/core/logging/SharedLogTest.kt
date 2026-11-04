@file:OptIn(ExperimentalPathApi::class)

package io.github.sameerkrmishra.jotter.core.logging

import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

private const val WRITERS = 4
private const val LINES = 250

/** The log file as several processes share it, and its modes, on the JVM. */
class SharedLogTest {
    private val root = createTempDirectory("jotter-shared-log")
    private val logs = root.resolve("logs")

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun mode(path: java.nio.file.Path) =
        PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

    private fun record(text: String) =
        LogRecord(Instant.fromEpochMilliseconds(0), LogLevel.INFO, "t", text, null)

    @Test
    fun theDirectoryAndFilesAreTheUsersAlone() {
        RotatingFileSink(logs.toOkioPath(), "cli").write(record("x"))
        assertEquals("rwx------", mode(logs))
        assertEquals("rw-------", mode(logs.resolve("cli.log")))
        assertEquals("rw-------", mode(logs.resolve("cli.lock")))
    }

    @Test
    fun widerModesOfOursAreNarrowed() {
        logs.createDirectories()
        Files.setPosixFilePermissions(logs, PosixFilePermissions.fromString("rwxr-xr-x"))
        val file = logs.resolve("cli.log")
        file.writeText("old\n")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))
        RotatingFileSink(logs.toOkioPath(), "cli").write(record("x"))
        assertEquals("rwx------", mode(logs))
        assertEquals("rw-------", mode(file))
        // Rotated files keep the mode.
        RotatingFileSink(logs.toOkioPath(), "cli", maxFileBytes = 1).write(record("y"))
        assertEquals("rw-------", mode(logs.resolve("cli.1.log")))
    }

    @Test
    fun processesWritingAtOnceLoseAndCutNoLineWhileTheyRotate() {
        val java = Path(System.getProperty("java.home"), "bin", "java").toString()
        val processes =
            (1..WRITERS).map {
                ProcessBuilder(
                    java,
                    "-cp",
                    System.getProperty("java.class.path"),
                    "io.github.sameerkrmishra.jotter.core.logging.LogWriterMainKt",
                    logs.toString(),
                    LINES.toString(),
                ).inheritIO().start()
            }
        processes.forEach {
            assertTrue(it.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS), "a writer hung")
            assertEquals(0, it.exitValue())
        }
        val lines =
            LogFiles(logs.toOkioPath())
                .recent("shared", Int.MAX_VALUE)
                .lines()
                .filter { it.isNotEmpty() }
        val line = Regex("""\S+ (\d+) INFO writer: line (\d+) [x]{100}""")
        assertTrue(lines.all(line::matches), lines.firstOrNull { !line.matches(it) })
        val byProcess = lines.groupBy { checkNotNull(line.matchEntire(it)).groupValues[1] }
        assertEquals(WRITERS, byProcess.size)
        byProcess.values.forEach { own ->
            // Each process's lines, all of them, in the order it wrote them.
            assertEquals(
                (0 until LINES).map(Int::toString),
                own.map { line.matchEntire(it)!!.groupValues[2] },
            )
        }
        assertTrue(Files.list(logs).count() > 2, "the log never rotated")
    }

    private companion object {
        const val TIMEOUT_SECONDS = 120L
    }
}
