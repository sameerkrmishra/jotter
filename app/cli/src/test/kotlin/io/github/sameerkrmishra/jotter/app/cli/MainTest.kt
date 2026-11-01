package io.github.sameerkrmishra.jotter.app.cli

import io.github.sameerkrmishra.jotter.core.storage.DATABASE_ENVVAR
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What only a real process shows: `main()` is outside the coverage gate and [CliTest], and Clikt's
 * test support supplies the exit status and the environment itself. Every run gets a temporary
 * `HOME`, so that no bug here can write to the database of whoever runs the tests.
 */
@OptIn(ExperimentalPathApi::class)
class MainTest {
    private val directory = createTempDirectory("jotter-main")
    private val home = directory.resolve("home")

    @AfterTest
    fun deleteDatabase() {
        directory.deleteRecursively()
    }

    private class Exit(
        val status: Int,
        val stdout: String,
        val stderr: String,
    )

    private fun jotter(
        vararg args: String,
        environment: Map<String, String> = emptyMap(),
        stdout: ProcessBuilder.Redirect = ProcessBuilder.Redirect.PIPE,
        closeStdout: Boolean = false,
    ): Exit {
        val javaBinary = Path(System.getProperty("java.home"), "bin", "java").toString()
        val command = listOf(javaBinary, "-cp", System.getProperty("java.class.path"), MAIN_CLASS)
        val builder = ProcessBuilder(command + args)
        builder.environment().apply {
            remove(DATABASE_ENVVAR)
            remove("XDG_DATA_HOME")
            put("HOME", home.toString())
            putAll(environment)
        }
        // In a file, as a full pipe would block a process that fails with a lot to say.
        val stderr = directory.resolve("stderr")
        val process = builder.redirectOutput(stdout).redirectError(stderr.toFile()).start()
        // Closed before the JVM is up, so its first write finds that nobody is reading.
        if (closeStdout) process.inputStream.close()
        val output = if (closeStdout) "" else process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS), "jotter did not exit")
        return Exit(process.exitValue(), output, stderr.readText())
    }

    @Test
    fun successExitsWithZeroAndUsesTheDefaultDatabase() {
        val exit = jotter("add", "buy milk")

        assertEquals(0, exit.status, exit.stderr)
        assertEquals(ID_LINE_LENGTH, exit.stdout.length, exit.stdout)
        assertTrue(home.resolve(".local/share/jotter/jotter.db").exists())
    }

    @Test
    fun theEnvironmentVariableNamesTheDatabase() {
        val database = directory.resolve("n.db")

        val exit = jotter("add", "x", environment = mapOf(DATABASE_ENVVAR to database.toString()))

        assertEquals(0, exit.status, exit.stderr)
        assertTrue(database.exists())
        assertFalse(home.exists())
    }

    @Test
    fun xdgDataHomeNamesTheDataDirectory() {
        val dataHome = directory.resolve("data")

        val exit = jotter("add", "x", environment = mapOf("XDG_DATA_HOME" to dataHome.toString()))

        assertEquals(0, exit.status, exit.stderr)
        assertTrue(dataHome.resolve("jotter/jotter.db").exists())
    }

    @Test
    fun aFailureExitsWithOneAndPrintsOnlyTheMessage() {
        val exit = jotter("show", "abc")

        assertEquals(1, exit.status)
        assertEquals("", exit.stdout)
        assertEquals("jotter: no note with id abc\n", exit.stderr)
    }

    @Test
    fun lostOutputIsAFailure() {
        val full = ProcessBuilder.Redirect.to(File("/dev/full"))

        val exit = jotter("add", "x", stdout = full)

        assertEquals(1, exit.status)
        assertEquals("jotter: cannot write to stdout\n", exit.stderr)
    }

    @Test
    fun aReaderThatLeftEndsTheProcessLikeAnyUnixFilter() {
        val database = mapOf(DATABASE_ENVVAR to directory.resolve("n.db").toString())
        assertEquals(0, jotter("add", "x", environment = database).status)

        val exit = jotter("list", environment = database, closeStdout = true)

        assertEquals(SIGPIPE_STATUS, exit.status)
        assertEquals("", exit.stderr)
    }

    @Test
    fun aUsageErrorIsNonZero() {
        val exit = jotter("nonsense")

        assertTrue(exit.status != 0)
        assertTrue("no such subcommand" in exit.stderr, exit.stderr)
    }

    private companion object {
        const val MAIN_CLASS = "io.github.sameerkrmishra.jotter.app.cli.MainKt"
        const val TIMEOUT_SECONDS = 60L

        /** 128 + 13, as a shell reports it. */
        const val SIGPIPE_STATUS = 141

        /** A canonical id and a newline. */
        const val ID_LINE_LENGTH = 37
    }
}
