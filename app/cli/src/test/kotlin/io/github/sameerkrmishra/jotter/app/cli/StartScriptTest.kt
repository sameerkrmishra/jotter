package io.github.sameerkrmishra.jotter.app.cli

import io.github.sameerkrmishra.jotter.core.storage.DATABASE_ENVVAR
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The installed start script, which [MainTest] bypasses. The rule it pins: the cache affects only
 * speed, never a command's exit status or output. A retried `add` would duplicate the note.
 *
 * Every process runs with a temporary `HOME` and database.
 */
@OptIn(ExperimentalPathApi::class)
class StartScriptTest {
    private val directory = createTempDirectory("jotter-script")
    private val home = directory.resolve("home").createDirectories()
    private val cache = home.resolve(".cache/jotter")
    private val database = directory.resolve("notes.db")
    private var installation = Path(System.getProperty("jotter.installation"))

    @AfterTest
    fun deleteEverything() {
        // The detached step must be done, or it would write into a deleted directory.
        awaitNoLock()
        cache.takeIf(Path::exists)?.setPosixFilePermissions(READ_WRITE)
        directory.deleteRecursively()
    }

    private fun start(
        vararg args: String,
        environment: Map<String, String> = mapOf("HOME" to home.toString()),
    ): JotterProcess {
        val command = listOf(installation.resolve("bin/jotter").toString()) + args
        val builder = ProcessBuilder(command)
        builder.environment().apply {
            clear()
            put("PATH", "/usr/bin:/bin")
            put("JAVA_HOME", System.getProperty("java.home"))
            put(DATABASE_ENVVAR, database.toString())
            putAll(environment)
        }
        return JotterProcess(builder, directory)
    }

    private fun jotter(
        vararg args: String,
        environment: Map<String, String> = mapOf("HOME" to home.toString()),
    ): Exit = start(*args, environment = environment).finish(TIMEOUT_SECONDS)

    private fun assertAdded(exit: Exit) {
        assertEquals(0, exit.status, exit.stdout + exit.stderr)
        assertEquals(ID_LINE_LENGTH, exit.stdout.length, exit.stdout)
        assertEquals("", exit.stderr)
    }

    private fun noteCount(): Int =
        NoteStore.open(database.toString(), "Test", EmptyCoroutineContext).use { store ->
            store.notes.list().size
        }

    private fun archives(): List<Path> =
        if (cache.exists()) cache.listDirectoryEntries("*.jsa") else emptyList()

    private fun locks(): List<Path> =
        if (cache.exists()) cache.listDirectoryEntries("*.lock") else emptyList()

    private fun await(
        what: String,
        condition: () -> Boolean,
    ) {
        val start = TimeSource.Monotonic.markNow()
        while (!condition()) {
            assertTrue(start.elapsedNow() < TIMEOUT_SECONDS.seconds, "Timed out: $what")
            Thread.sleep(POLL_MILLIS)
        }
    }

    /** The JVM makes archives read-only. */
    private fun overwrite(
        archive: Path,
        text: String,
    ) {
        archive.deleteExisting()
        archive.writeText(text)
    }

    private fun awaitNoLock() = await("the archive step to end") { locks().isEmpty() }

    private fun awaitArchive(): Path {
        await("an archive") { archives().size == 1 && locks().isEmpty() }
        return archives().single()
    }

    @Test
    fun aFirstRunWorksAndLeavesAnArchiveBehindWithoutHoldingUpTheCommand() {
        assertAdded(jotter("add", "x"))

        val archive = awaitArchive()

        assertAdded(jotter("add", "y"))
        assertEquals(listOf(archive), cache.listDirectoryEntries())
        assertEquals(2, noteCount())
    }

    @Test
    fun aReadOnlyCacheChangesNothing() {
        cache.createDirectories().setPosixFilePermissions(READ_ONLY)

        assertAdded(jotter("add", "x"))
        assertAdded(jotter("add", "y"))

        assertEquals(2, noteCount())
    }

    @Test
    fun anUnwritableParentOfTheCacheChangesNothing() {
        home.resolve(".cache").writeText("a file, not a directory")

        assertAdded(jotter("add", "x"))
    }

    @Test
    fun anUnsetHomeChangesNothing() {
        assertAdded(jotter("add", "x", environment = emptyMap()))
        assertEquals(1, noteCount())
    }

    @Test
    fun anEmptyHomeChangesNothing() {
        assertAdded(jotter("add", "x", environment = mapOf("HOME" to "")))
        assertEquals(1, noteCount())
    }

    @Test
    fun parallelFirstRunsAllSucceedAndMakeOneArchive() {
        val runs = List(PARALLEL_RUNS) { start("add", "note $it") }

        runs.forEach { assertAdded(it.finish(TIMEOUT_SECONDS)) }

        awaitArchive()
        assertEquals(PARALLEL_RUNS, noteCount())
    }

    @Test
    fun aGarbageArchiveIsSilentAndReplaced() {
        cache.createDirectories()
        assertAdded(jotter("add", "x"))
        val archive = awaitArchive()
        overwrite(archive, "garbage, not a class data archive")
        val garbageSize = archive.fileSize()

        assertAdded(jotter("add", "y"))

        await("a new archive") {
            locks().isEmpty() && archive.exists() && archive.fileSize() > garbageSize
        }
    }

    @Test
    fun anEmptyArchiveIsSilentAndReplaced() {
        assertAdded(jotter("add", "x"))
        val archive = awaitArchive()
        overwrite(archive, "")

        assertAdded(jotter("add", "y"))

        await("a new archive") { locks().isEmpty() && archive.exists() && archive.fileSize() > 0 }
    }

    @Test
    fun replacingAJarReplacesTheArchive() {
        val copy = directory.resolve("installation")
        installation.copyToRecursively(copy, followLinks = false)
        installation = copy
        assertAdded(jotter("add", "x"))
        val archive = awaitArchive()
        val made = archive.getLastModifiedTime()
        val later = FileTime.fromMillis(made.toMillis() + LATER_MILLIS)
        copy.resolve("lib/cli.jar").setLastModifiedTime(later)

        assertAdded(jotter("add", "y"))

        await("a new archive") {
            locks().isEmpty() && archive.exists() && archive.getLastModifiedTime() > made
        }
    }

    @Test
    fun aFailureKeepsItsExitStatus() {
        val exit = jotter("show", "abc")

        assertEquals(1, exit.status)
        assertEquals("jotter: no note with id abc\n", exit.stderr)
        assertFalse(database.resolveSibling("stray").exists())
    }

    private companion object {
        const val TIMEOUT_SECONDS = 60L
        const val POLL_MILLIS = 50L
        const val PARALLEL_RUNS = 8

        /** A canonical id and a newline. */
        const val ID_LINE_LENGTH = 37

        /** Far enough for the file system's clock, and the script's -nt, to see the difference. */
        const val LATER_MILLIS = 5_000L

        val READ_ONLY: Set<PosixFilePermission> = PosixFilePermissions.fromString("r-xr-xr-x")
        val READ_WRITE: Set<PosixFilePermission> = PosixFilePermissions.fromString("rwxr-xr-x")
    }
}
