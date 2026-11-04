package io.github.sameerkrmishra.jotter.app.cli

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.storage.MAX_NOTE_CHARS
import io.github.sameerkrmishra.jotter.core.storage.NoteService
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Runs the commands as `main()` does, against a temporary database. */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class CliTest {
    private val directory = createTempDirectory("jotter-cli")
    private val database = directory.resolve("notes.db")

    @AfterTest
    fun deleteDatabase() {
        directory.deleteRecursively()
    }

    private fun jotter(
        vararg args: String,
        stdin: String = "",
        file: Path = database,
        input: InputStream = stdin.byteInputStream(),
    ): CliResult = runJotter(args.toList(), file, input)

    /** Runs the command, checks that it worked, and returns its output lines. */
    private fun lines(vararg args: String): List<String> {
        val result = jotter(*args)
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals("", result.stderr)
        return result.stdout.lines().dropLast(1)
    }

    private fun add(vararg args: String): String = lines("add", *args).single()

    private fun columns(vararg args: String) = lines(*args).map { it.split('\t') }

    private fun <T> withStore(block: (NoteService) -> T): T =
        NoteStore.open(database.toString(), "Test", EmptyCoroutineContext).use { block(it.notes) }

    private fun assertFails(
        expected: String,
        result: CliResult,
    ) {
        assertNotEquals(0, result.statusCode)
        assertEquals("", result.stdout)
        assertEquals(
            1,
            result.stderr
                .trimEnd()
                .lines()
                .size,
            result.stderr,
        )
        assertTrue(expected in result.stderr, result.stderr)
        assertFalse("Exception" in result.stderr, result.stderr)
    }

    @Test
    fun addPrintsOnlyTheNewId() {
        val id = add("buy milk")

        assertEquals(id, NoteId.parse(id).toString())
        val note = withStore { it.get(NoteId.parse(id)) }
        assertEquals("buy milk", note?.body)
        assertEquals("", note?.title)
    }

    @Test
    fun addStoresTheTitleAndEveryTag() {
        val id = add("--title", "Groceries", "--tag", "Shopping", "--tag", " two  words ", "milk")

        val note = checkNotNull(withStore { it.get(NoteId.parse(id)) })
        assertEquals("Groceries", note.title)
        assertEquals(setOf("Shopping", "two words"), note.tags.map { it.toString() }.toSet())
    }

    @Test
    fun aNoteOverTheSizeLimitFailsWithAClearError() {
        val tooLong = "x".repeat(MAX_NOTE_CHARS + 1)

        assertFails("the note is too long", jotter("add", "-", stdin = tooLong))
    }

    @Test
    fun addReadsTheNoteFromStdinWhenGivenADash() {
        val result = jotter("add", "-", stdin = "first\n\nGrüße ✓\n\n")

        assertEquals(0, result.statusCode, result.stderr)
        val note = withStore { it.get(NoteId.parse(result.stdout.trim())) }
        assertEquals("first\n\nGrüße ✓", note?.body)
    }

    private fun body(id: String): String? = withStore { it.get(NoteId.parse(id)) }?.body

    @Test
    fun textStartingWithADashIsTheNote() {
        val texts =
            listOf(
                "- [ ] milk",
                "-[ ] milk",
                "- item",
                "-1 degrees",
                "-1",
                "-milk",
                "-hello",
                "-hat",
                "---",
                "--- a heading rule",
                "-- two dashes",
                "--force is risky",
            )

        for (text in texts) {
            assertEquals(text, body(add(text)), text)
        }
    }

    @Test
    fun optionsWorkOnEitherSideOfTextStartingWithADash() {
        val forms =
            listOf(
                listOf("--tag", "home", "--title", "T", "- item"),
                listOf("- item", "--tag", "home", "--title", "T"),
                listOf("--tag", "home", "- item", "--title", "T"),
                listOf("--tag=home", "--title=T", "- item"),
            )

        for (form in forms) {
            val note = checkNotNull(withStore { it.get(NoteId.parse(add(*form.toTypedArray()))) })
            assertEquals("- item", note.body, "$form")
            assertEquals("T", note.title, "$form")
            assertEquals(listOf("home"), note.tags.map { it.toString() }, "$form")
        }
    }

    @Test
    fun anOptionValueMayStartWithADash() {
        val note =
            withStore { it.get(NoteId.parse(add("--title", "- title", "--tag", "-x", "text"))) }

        assertEquals("- title", note?.title)
        assertEquals(listOf("-x"), note?.tags?.map { it.toString() })
    }

    @Test
    fun aMistypedOptionIsAnErrorAndStoresNothing() {
        val mistakes =
            listOf(
                listOf("add", "--tga"),
                listOf("add", "--tga=x"),
                listOf("add", "--tga=two words"),
                listOf("add", "--tga", "x"),
                listOf("add", "--tga", "x", "milk"),
                listOf("add", "milk", "--tga", "x"),
                listOf("add", "milk", "--tga"),
            )

        for (mistake in mistakes) {
            val result = jotter(*mistake.toTypedArray())

            assertNotEquals(0, result.statusCode, "$mistake")
            assertEquals("", result.stdout, "$mistake")
            assertFalse(database.exists(), "$mistake must not touch the database")
        }
    }

    @Test
    fun theErrorForAMistypedOptionSaysHowToStoreItAnyway() {
        val result = jotter("add", "--tga")

        assertTrue("no such option --tga" in result.stderr, result.stderr)
        assertTrue("put -- before it" in result.stderr, result.stderr)
    }

    @Test
    fun aDoubleDashMakesAnythingTheNote() {
        for (text in listOf("- [ ] milk", "--tga", "--tga=x", "--tag", "-h")) {
            assertEquals(text, body(add("--", text)), text)
        }
        assertEquals("- x", body(add("--tag", "t", "--", "- x")))
    }

    @Test
    fun helpAndStdinKeepTheirMeaning() {
        val help = jotter("add", "-h")

        assertEquals(0, help.statusCode)
        assertTrue("Usage" in help.stdout + help.stderr)
        assertFalse(database.exists())
        val result = jotter("add", "-", stdin = "- [ ] from stdin\n")
        assertEquals("- [ ] from stdin", body(result.stdout.trim()))
    }

    @Test
    fun otherCommandsStillRefuseUnknownOptions() {
        val result = jotter("list", "--tga", "x")

        assertNotEquals(0, result.statusCode)
        assertTrue("no such option" in result.stderr, result.stderr)
    }

    @Test
    fun addRefusesAnEmptyNoteButAcceptsOneWithOnlyATitle() {
        assertFails("the note is empty", jotter("add", " "))
        assertFails("the note is empty", jotter("add", "-", stdin = "\n"))

        val id = NoteId.parse(add("--title", "Only a title", ""))
        assertEquals("", withStore { it.get(id) }?.body)
    }

    @Test
    fun anInvalidTagIsAUsageError() {
        for (args in listOf(listOf("add", "--tag", " ", "x"), listOf("list", "--tag", " "))) {
            val result = jotter(*args.toTypedArray())

            assertNotEquals(0, result.statusCode)
            assertTrue("not a valid tag" in result.stderr, result.stderr)
            assertFalse(database.exists(), "A usage error must not create the database")
        }
    }

    @Test
    fun listPrintsOneTabSeparatedLinePerNote() {
        val plain = add("first line\nsecond line")
        val full = add("--title", "A title", "--tag", "b", "--tag", "a", "body")
        withStore { it.setPinned(NoteId.parse(full), true) }

        val rows = columns("list")

        assertEquals(listOf(full, plain), rows.map { it[0] })
        val time = Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ""")
        assertTrue(rows.all { time.matches(it[1]) }, "$rows")
        assertEquals(listOf("pinned", "a,b", "A title"), rows[0].drop(2))
        assertEquals(listOf("-", "-", "first line"), rows[1].drop(2))
    }

    @Test
    fun aNoteIsOneLineWhateverItsTitleHoldsAndABlankTitleFallsBackToTheBody() {
        val id = add("--title", "a\tb\nc", "body")
        val blank = add("--title", " ", "\n\n  text")

        val rows = columns("list").associateBy { it[0] }

        assertEquals(2, rows.size)
        assertEquals("a b c", rows.getValue(id)[4])
        assertEquals("text", rows.getValue(blank)[4])
    }

    @Test
    fun listFiltersByTagAndState() {
        val active = add("--tag", "work", "active")
        add("other")
        val archived = add("--tag", "work", "archived")
        val trashed = add("trashed")
        withStore {
            it.setState(NoteId.parse(archived), NoteState.ARCHIVED)
            it.setState(NoteId.parse(trashed), NoteState.TRASHED)
        }

        assertEquals(listOf(active), columns("list", "--tag", "work").map { it[0] })
        assertEquals(listOf(archived), columns("list", "--archived").map { it[0] })
        assertEquals(listOf(archived), columns("list", "--archived", "--tag", "work").map { it[0] })
        assertEquals(listOf(trashed), columns("list", "--trash").map { it[0] })
    }

    @Test
    fun listOfNothingPrintsNothing() {
        assertEquals(emptyList(), lines("list"))
        assertEquals(emptyList(), lines("search", "x"))
        assertEquals(emptyList(), lines("tags"))
    }

    @Test
    fun showPrintsHeadersThenTheBodyAsItIs() {
        val id = add("--title", "Title", "--tag", "b", "--tag", "a", "line 1\n\n  line 2")
        withStore { it.setPinned(NoteId.parse(id), true) }

        val lines = lines("show", id)

        assertEquals("id: $id", lines[0])
        assertEquals("title: Title", lines[1])
        assertEquals("tags: a,b", lines[2])
        assertEquals("state: active", lines[3])
        assertEquals("pinned: yes", lines[4])
        assertTrue(lines[5].startsWith("created: 20"), lines[5])
        assertTrue(lines[6].startsWith("updated: 20"), lines[6])
        assertEquals(listOf("", "line 1", "", "  line 2"), lines.drop(7))
    }

    @Test
    fun showNamesEveryState() {
        val id = add("x")
        val noteId = NoteId.parse(id)

        assertTrue("pinned: no" in lines("show", id))
        withStore { it.setState(noteId, NoteState.ARCHIVED) }
        assertTrue("state: archived" in lines("show", id))
        withStore { it.setState(noteId, NoteState.TRASHED) }
        assertTrue("state: trash" in lines("show", id))
    }

    @Test
    fun aUniquePrefixNamesANoteInAnyState() {
        val id = add("x")
        withStore { it.setState(NoteId.parse(id), NoteState.TRASHED) }

        for (prefix in listOf(id.take(13), id.take(13).uppercase(), id.take(13).replace("-", ""))) {
            assertEquals("id: $id", lines("show", prefix).first())
        }
    }

    @Test
    fun aWholeIdIsAcceptedInItsOtherForms() {
        val id = add("x")

        assertEquals("id: $id", lines("show", id.uppercase()).first())
        assertEquals("id: $id", lines("show", id.replace("-", "")).first())
    }

    @Test
    fun aPrefixOfTwoNotesIsAmbiguous() {
        val ids = List(2) { add("note $it") }
        val common = ids[0].commonPrefixWith(ids[1])

        assertFails("ambiguous: 2 notes match", jotter("show", common))
    }

    @Test
    fun aMissingNoteIsAnError() {
        add("x")

        assertFails("no note with id zzz", jotter("show", "zzz"))
        assertFails("no note with id -", jotter("show", "--", "-"))
        assertFails(
            "no note with id 00000000-0000-0000-0000-000000000000",
            jotter("show", "00000000-0000-0000-0000-000000000000"),
        )
    }

    @Test
    fun searchPrintsTheIdTheSummaryAndASnippetOnOneLine() {
        val id = add("--title", "Groceries", "buy\tmilk\nand eggs")
        add("nothing relevant")

        assertEquals(listOf(listOf(id, "Groceries", "buy milk and eggs")), columns("search", "mil"))
    }

    @Test
    fun tagsPrintsEachTagWithItsCount() {
        add("--tag", "Work", "--tag", "a b", "1")
        add("--tag", "Work", "2")

        assertEquals(listOf("Work\t2", "a b\t1"), lines("tags"))
    }

    @Test
    fun theDatabaseOptionWinsOverTheEnvironment() {
        val other = directory.resolve("other.db")

        lines("--db", other.toString(), "add", "x")

        assertTrue(other.exists())
        assertFalse(database.exists())
    }

    @Test
    fun theDatabaseDirectoryIsCreated() {
        val nested = directory.resolve("a/b/jotter.db")

        assertEquals(0, jotter("add", "x", file = nested).statusCode)

        assertTrue(nested.exists())
    }

    @Test
    fun aDirectoryThatCannotBeCreatedIsAnError() {
        val file = directory.resolve("file")
        file.writeText("x")

        assertFails(
            "Jotter cannot create the folder for",
            jotter("list", file = file.resolve("jotter.db")),
        )
    }

    @Test
    fun aReadOnlyDatabaseIsSaidInPlainWordsAlsoWhenItCloses() {
        add("x")
        Files.setPosixFilePermissions(database, PosixFilePermissions.fromString("r--r--r--"))
        try {
            // The second run reads the notes, then cannot checkpoint the WAL on close.
            repeat(2) {
                val result = jotter("list")
                assertFalse("unexpected" in result.stderr, result.stderr)
                assertFalse("Exception" in result.stderr, result.stderr)
                if (result.statusCode != 0) {
                    assertTrue("not allowed to change" in result.stderr, result.stderr)
                }
            }
        } finally {
            Files.setPosixFilePermissions(database, PosixFilePermissions.fromString("rw-------"))
        }
    }

    @Test
    fun aFileThatIsNotADatabaseIsRefusedAndLeftAlone() {
        database.writeText("Not a database, but long enough for a header of SQLite. ".repeat(9))
        val before = database.readText()

        assertFails("$database is damaged, so Jotter cannot read it", jotter("add", "x"))

        assertEquals(before, database.readText())
    }

    @Test
    fun aPathSqliteCannotOpenIsNamedInTheError() {
        assertFails(
            "Jotter cannot open $directory. Check that its folder exists",
            jotter("list", file = directory),
        )
    }

    @Test
    fun theRootDirectoryIsAnOrdinaryFailure() {
        val result = jotter("--db", "/", "list")

        assertFails("Jotter cannot open /.", result)
    }

    @Test
    fun aStdinThatCannotBeReadIsAnError() {
        val failing =
            object : InputStream() {
                override fun read(): Int = throw IOException("Is a directory")
            }

        assertFails("cannot read stdin: Is a directory", jotter("add", "-", input = failing))
    }

    @Test
    fun anotherApplicationsDatabaseIsRefusedAndLeftAlone() {
        rawConnection().use { it.execSQL("CREATE TABLE theirs (x)") }
        val before = Files.readAllBytes(database)

        assertFails("is not a Jotter notes file", jotter("add", "x"))

        assertTrue(before.contentEquals(Files.readAllBytes(database)))
    }

    @Test
    fun aNewerDatabaseIsRefused() {
        lines("list")
        rawConnection().use { it.execSQL("PRAGMA user_version = 999") }

        assertFails("saved by a newer version of Jotter", jotter("list"))
    }

    @Test
    fun aDatabaseLockedDuringAWriteIsReported() {
        lines("list")
        rawConnection().use { lock ->
            lock.execSQL("BEGIN IMMEDIATE")

            assertFails("Another Jotter program is using the notes", jotter("add", "x"))

            lock.execSQL("ROLLBACK")
        }
    }

    @Test
    fun aDatabaseLockedDuringOpenIsReported() {
        // In rollback mode a writer blocks readers too, so NoteStore.open's first read fails.
        rawConnection().use { lock ->
            lock.execSQL("BEGIN EXCLUSIVE")
            lock.execSQL("CREATE TABLE being_created (x)")

            assertFails("in use by another Jotter program", jotter("list"))

            lock.execSQL("ROLLBACK")
        }
    }

    private fun rawConnection() = BundledSQLiteDriver().open(database.toString())
}
