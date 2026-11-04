package io.github.sameerkrmishra.jotter.app.cli

import io.github.sameerkrmishra.jotter.app.sync.DEBUG_LOG_PREAMBLE
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `jotter debug-log`, which prints all it holds and writes nothing unless asked to. */
@OptIn(ExperimentalPathApi::class)
class DebugLogCommandTest {
    private val directory = createTempDirectory("jotter-debug-log")
    private val database = directory.resolve("notes.db")

    @AfterTest
    fun deleteEverything() {
        directory.deleteRecursively()
    }

    private fun jotter(vararg args: String) = runJotter(args.toList(), database)

    @Test
    fun itPrintsTheWholeDebugLog() {
        jotter("add", "--title", "A private title", "private text")
        val result = jotter("debug-log")
        assertEquals(0, result.statusCode, result.stderr)
        assertContains(result.stdout, DEBUG_LOG_PREAMBLE)
        assertContains(result.stdout, "App: cli unknown")
        assertContains(result.stdout, "Backends: 0")
        assertFalse("private" in result.stdout)
    }

    @Test
    fun itWorksWhenTheNotesCannotBeOpened() {
        database.writeText("Not a database, but long enough for a header of SQLite. ".repeat(9))
        val result = jotter("debug-log")
        assertEquals(0, result.statusCode, result.stderr)
        assertContains(result.stdout, "The notes could not be opened: Your notes file $database is")
        assertFalse("Backends" in result.stdout)
    }

    @Test
    fun itWritesANewFileWithTheSameText() {
        val file = directory.resolve("debug.txt")
        val result = jotter("debug-log", "--output", file.toString())
        assertEquals(0, result.statusCode, result.stderr)
        assertContains(result.stdout, "Wrote the debug log to $file")
        assertContains(file.readText(), DEBUG_LOG_PREAMBLE)
    }

    @Test
    fun itNeverOverwritesAFile() {
        val file = directory.resolve("debug.txt")
        file.writeText("keep me")
        val result = jotter("debug-log", "-o", file.toString())
        assertEquals(1, result.statusCode)
        assertContains(result.stderr, "exists already")
        assertEquals("keep me", file.readText())
    }

    @Test
    fun aFolderThatIsNotThereIsSaid() {
        val result = jotter("debug-log", "-o", directory.resolve("missing/debug.txt").toString())
        assertEquals(1, result.statusCode)
        assertContains(result.stderr, "Check that its folder exists")
        assertFalse("Exception" in result.stderr)
    }

    @Test
    fun theOutputMustBeAPath() {
        val result = jotter("debug-log", "-o", "a\u0000b")
        assertTrue(result.statusCode != 0)
        assertContains(result.stderr, "not a valid path")
    }

    @Test
    fun thePlatformNamesTheSystemAndJava() {
        val properties =
            mapOf(
                "os.name" to "Linux",
                "os.version" to "6.18",
                "os.arch" to "amd64",
                "java.version" to "21.0.9",
            )
        assertEquals("Linux 6.18 (amd64), Java 21.0.9", jvmPlatform(properties::get))
    }
}
