package io.github.sameerkrmishra.jotter.app.cli

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.storage.NoteFilter
import io.github.sameerkrmishra.jotter.core.storage.NoteService
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import java.io.RandomAccessFile
import java.nio.file.Path
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** `jotter import`, on synthetic notes in a temporary directory. */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class ImportCommandTest {
    private val directory = createTempDirectory("jotter-import")
    private val database = directory.resolve("notes.db")
    private val keep = directory.resolve("Keep").createDirectories()

    @AfterTest
    fun deleteFiles() {
        directory.deleteRecursively()
    }

    private fun jotter(vararg args: String) = runJotter(args.toList(), database)

    private fun importKeep() = jotter("import", keep.toString())

    private fun writeNote(
        name: String,
        json: String,
    ): Path = keep.resolve(name).apply { writeText(json) }

    private fun <T> withStore(block: (NoteService) -> T): T =
        NoteStore.open(database.toString(), "Test", EmptyCoroutineContext).use { block(it.notes) }

    private fun summary(
        imported: Int = 0,
        skipped: Int = 0,
        ignored: Int = 0,
        warnings: Int = 0,
        failures: Int = 0,
    ) = "imported\t$imported\nskipped\t$skipped\nignored\t$ignored\n" +
        "warnings\t$warnings\nfailures\t$failures\n"

    private fun assertFailsCleanly(
        expected: String,
        result: CliResult,
    ) {
        assertNotEquals(0, result.statusCode)
        assertEquals("", result.stdout)
        assertTrue(expected in result.stderr, result.stderr)
        assertFalse("Exception" in result.stderr, result.stderr)
    }

    @Test
    fun importsADirectoryAndIgnoresWhatIsNotJson() {
        writeNote("a.json", keepNote(1_000_000, title = "First"))
        writeNote("b.JSON", keepNote(2_000_000, title = "Second"))
        keep.resolve("a.html").writeText("<html>")
        keep.resolve("photo.jpg").writeBytes(byteArrayOf(-1, -40))
        keep.resolve("folder.json").createDirectories()

        val result = importKeep()

        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(summary(imported = 2, ignored = 3), result.stdout)
        assertEquals("", result.stderr)
        assertEquals(setOf("First", "Second"), withStore { n -> n.list().map { it.title }.toSet() })
    }

    @Test
    fun importsASingleFile() {
        val file = writeNote("a.json", keepNote(1_000_000))

        val result = jotter("import", file.toString())

        assertEquals(summary(imported = 1), result.stdout)
    }

    @Test
    fun importingAgainSkipsEverything() {
        writeNote("a.json", keepNote(1_000_000))

        importKeep()
        val again = importKeep()

        assertEquals(0, again.statusCode, again.stderr)
        assertEquals(summary(skipped = 1), again.stdout)
        assertEquals(1, withStore { it.list().size })
    }

    @Test
    fun theStatesOfKeepArriveInTheDatabase() {
        writeNote("a.json", keepNote(1_000_000, more = ""","isArchived":true"""))
        writeNote("b.json", keepNote(2_000_000, more = ""","isTrashed":true"""))

        importKeep()

        assertEquals(1, withStore { it.list(NoteFilter(NoteState.ARCHIVED)).size })
        assertEquals(1, withStore { it.list(NoteFilter(NoteState.TRASHED)).size })
    }

    @Test
    fun warningsGoToStderrAndExitWithZero() {
        val attachment = ""","attachments":[{"filePath":"cat.png","mimetype":"image/png"}]"""
        val text = "the secret text"
        writeNote("a.json", keepNote(1_000_000, text = text, more = attachment))

        val result = importKeep()

        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(summary(imported = 1, warnings = 1), result.stdout)
        assertEquals("warning\ta.json\tATTACHMENT_DROPPED\tcat.png\n", result.stderr)
    }

    @Test
    fun aWarningWithoutDetailHasADash() {
        writeNote("a.json", """{"title":"t","createdTimestampUsec":1000000}""")

        val result = importKeep()

        assertEquals("warning\ta.json\tMISSING_EDITED_TIME\t-\n", result.stderr)
    }

    @Test
    fun aFailureIsReportedWithoutTheTextAndTheRestIsImported() {
        writeNote("a.json", """{"textContent": "the secret text" oops""")
        writeNote("b.json", keepNote(1_000_000))

        val result = importKeep()

        assertEquals(1, result.statusCode)
        assertEquals(summary(imported = 1, failures = 1), result.stdout)
        assertFalse("secret" in result.stdout + result.stderr, result.stderr)
        val lines = result.stderr.trimEnd().lines()
        assertEquals("failure\ta.json\tMALFORMED_JSON\tNot a valid Keep note", lines[0])
        assertEquals(2, lines.size, result.stderr)
        assertTrue("Running the import again is safe" in lines[1], lines[1])
    }

    @Test
    fun theLaterNameOfADuplicateFails() {
        writeNote("b.json", keepNote(1_000_000))
        writeNote("a.json", keepNote(1_000_000))

        val result = importKeep()

        assertEquals(summary(imported = 1, failures = 1), result.stdout)
        assertTrue("failure\tb.json\tDUPLICATE_ID\ta.json\n" in result.stderr, result.stderr)
    }

    @Test
    fun aFileNameCannotAddALineToTheOutput() {
        writeNote("two\nlines.json", "not json")

        val result = importKeep()

        assertTrue("failure\ttwo lines.json\tMALFORMED_JSON" in result.stderr, result.stderr)
    }

    @Test
    fun filesThatCannotBeReadDoNotStopTheRun() {
        keep.resolve("a.json").writeBytes(byteArrayOf('"'.code.toByte(), -1, '"'.code.toByte()))
        keep.resolve("b.json").createSymbolicLinkPointingTo(keep.resolve("gone"))
        writeNote("c.json", keepNote(1_000_000))

        val result = importKeep()

        assertEquals(1, result.statusCode)
        assertEquals(summary(imported = 1, failures = 2), result.stdout)
        assertTrue("failure\ta.json\tREAD_ERROR\tNot valid UTF-8\n" in result.stderr, result.stderr)
        assertTrue("failure\tb.json\tREAD_ERROR\t" in result.stderr, result.stderr)
    }

    @Test
    fun aLockedDatabaseStopsTheRunAndCanBeContinued() {
        writeNote("a.json", keepNote(1_000_000))
        writeNote("b.json", keepNote(2_000_000))
        jotter("list")

        val stopped =
            BundledSQLiteDriver().open(database.toString()).use { lock ->
                lock.execSQL("BEGIN IMMEDIATE")
                importKeep().also { lock.execSQL("ROLLBACK") }
            }

        assertEquals(1, stopped.statusCode)
        assertEquals(summary(failures = 1), stopped.stdout)
        assertTrue("failure\ta.json\tSTORAGE_BUSY\t" in stopped.stderr, stopped.stderr)
        assertTrue("stayed locked" in stopped.stderr, stopped.stderr)
        assertTrue("Running the import again is safe" in stopped.stderr, stopped.stderr)
        assertEquals(summary(imported = 2), importKeep().stdout)
    }

    @Test
    fun aMissingPathFailsAndCreatesNoDatabase() {
        val result = jotter("import", directory.resolve("nope").toString())

        assertFailsCleanly("no such file or directory", result)
        assertFalse(database.exists())
    }

    @Test
    fun aHugeFileIsAFailureAndTheRestIsImported() {
        // Sparse, so it takes no disk space, and past what a String can hold.
        RandomAccessFile(keep.resolve("big.json").toFile(), "rw").use { it.setLength(HUGE_FILE) }
        writeNote("c.json", keepNote(1_000_000))

        val result = importKeep()

        assertEquals(1, result.statusCode)
        assertEquals(summary(imported = 1, failures = 1), result.stdout)
        val expected = "failure\tbig.json\tREAD_ERROR\tLarger than 64 MiB\n"
        assertTrue(expected in result.stderr, result.stderr)
    }

    @Test
    fun theTakeoutDirectoryPointsToItsKeepDirectory() {
        val result = jotter("import", directory.toString())

        assertFailsCleanly("no .json files in $directory, try $keep\n", result)
        assertFalse(database.exists())
    }

    @Test
    fun aDirectoryWithoutJsonFilesFails() {
        keep.resolve("a.html").writeText("<html>")

        assertFailsCleanly("no .json files in $keep\n", importKeep())
        assertFalse(database.exists())
    }

    @Test
    fun aFileThatIsNotJsonIsRefused() {
        val file = keep.resolve("a.html").apply { writeText("<html>") }

        assertFailsCleanly("not a directory or a .json file", jotter("import", file.toString()))
    }

    @Test
    fun aPathThatCannotBeExaminedIsReported() {
        val file = keep.resolve("a.html").apply { writeText("<html>") }

        // ENOTDIR: the path exists no more than a missing one, but the error is another.
        val result = jotter("import", file.resolve("below").toString())

        assertFailsCleanly("cannot read", result)
    }

    @Test
    fun anEmptyPathIsRefused() {
        assertTrue("the path is empty" in jotter("import", "").stderr)
    }

    @Test
    fun aNoteFileIsReadOnlyWhenItsTurnComes() {
        val first = writeNote("a.json", keepNote(1_000_000))
        val second = writeNote("b.json", "before")
        val documents = KeepFileReader().documents(listOf(first, second)).iterator()

        documents.next()
        second.writeText("after")

        assertEquals("after", documents.next().json)
    }

    private companion object {
        /** 3 GiB. */
        const val HUGE_FILE = 3L * 1024 * 1024 * 1024
    }
}
