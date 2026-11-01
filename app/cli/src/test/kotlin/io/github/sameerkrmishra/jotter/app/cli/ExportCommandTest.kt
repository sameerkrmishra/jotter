package io.github.sameerkrmishra.jotter.app.cli

import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.storage.NoteService
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** `jotter export`, on synthetic notes in a temporary directory. */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class ExportCommandTest {
    private val directory = createTempDirectory("jotter-export")
    private val database = directory.resolve("notes.db")
    private val out = directory.resolve("out")

    @AfterTest
    fun deleteFiles() {
        directory.deleteRecursively()
    }

    private fun jotter(vararg args: String) = runJotter(args.toList(), database)

    private fun <T> withStore(block: (NoteService) -> T): T =
        NoteStore.open(database.toString(), "Test", EmptyCoroutineContext).use { block(it.notes) }

    private fun exportedNames() = out.listDirectoryEntries().map { it.name }.sorted()

    @Test
    fun exportLeavesOutTheTrashUnlessAsked() {
        val active = jotter("add", "--title", "Active", "x").stdout.trim()
        val archived = jotter("add", "--title", "Archived", "x").stdout.trim()
        val trashed = jotter("add", "--title", "Trashed", "x").stdout.trim()
        withStore {
            it.setState(NoteId.parse(archived), NoteState.ARCHIVED)
            it.setState(NoteId.parse(trashed), NoteState.TRASHED)
        }

        val result = jotter("export", out.toString())

        assertEquals(0, result.statusCode, result.stderr)
        assertEquals("exported\t2\n", result.stdout)
        assertEquals("", result.stderr)
        assertEquals(listOf("Active.md", "Archived.md"), exportedNames())
        assertTrue("id: $active" in out.resolve("Active.md").readText())

        assertEquals("exported\t3\n", jotter("export", "--trash", out.toString()).stdout)
        assertEquals(listOf("Active.md", "Archived.md", "Trashed.md"), exportedNames())
    }

    @Test
    fun exportFiltersByTag() {
        jotter("add", "--title", "Soup", "--tag", "recipes", "x")
        jotter("add", "--title", "Milk", "x")

        val result = jotter("export", "--tag", "recipes", out.toString())

        assertEquals("exported\t1\n", result.stdout)
        assertEquals(listOf("Soup.md"), exportedNames())
    }

    @Test
    fun anInvalidExportTagIsRefused() {
        assertTrue("not a valid tag" in jotter("export", "--tag", "", out.toString()).stderr)
    }

    @Test
    fun exportReportsARenamedFile() {
        jotter("add", "--title", "Soup", "x")
        out.createDirectories().resolve("Soup.md").writeText("my own file")

        val result = jotter("export", out.toString())

        assertEquals(0, result.statusCode, result.stderr)
        assertEquals("exported\t1\n", result.stdout)
        assertTrue(result.stderr.startsWith("renamed\tSoup.md\tSoup "), result.stderr)
        assertEquals("my own file", out.resolve("Soup.md").readText())
    }

    @Test
    fun anEmptyTargetIsRefused() {
        assertTrue("the path is empty" in jotter("export", "").stderr)
    }

    @Test
    fun exportIntoAFileFailsCleanly() {
        jotter("add", "x")
        Files.writeString(out, "a file")

        val result = jotter("export", out.toString())

        assertNotEquals(0, result.statusCode)
        assertEquals("", result.stdout)
        assertTrue("cannot export to $out" in result.stderr, result.stderr)
        assertFalse("Exception" in result.stderr, result.stderr)
    }
}
