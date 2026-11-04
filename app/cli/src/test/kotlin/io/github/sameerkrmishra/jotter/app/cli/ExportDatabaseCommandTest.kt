package io.github.sameerkrmishra.jotter.app.cli

import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `jotter export-database`: one sound file, never over another without `--replace`. */
@OptIn(ExperimentalPathApi::class)
class ExportDatabaseCommandTest {
    private val directory = createTempDirectory("jotter-export-database")
    private val database = directory.resolve("notes.db")
    private val out = Files.createDirectory(directory.resolve("out"))

    @AfterTest
    fun deleteEverything() {
        directory.deleteRecursively()
    }

    private fun jotter(
        vararg args: String,
        file: Path = database,
    ) = runJotter(args.toList(), file)

    @Test
    fun theCopyOpensAsTheSameNotes() {
        jotter("add", "--title", "Shopping", "--tag", "home", "milk")
        val copy = out.resolve("jotter.db")
        val result = jotter("export-database", copy.toString())
        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(
            "Exported your notes to $copy. Passwords and pairing tokens are not in it: after " +
                "restoring it, enter them again.\n",
            result.stdout,
        )
        assertEquals(listOf(copy), out.listDirectoryEntries())
        val listed = jotter("list", file = copy)
        assertEquals(0, listed.statusCode, listed.stderr)
        assertContains(listed.stdout, "Shopping")
        assertEquals(jotter("list").stdout, listed.stdout)
    }

    @Test
    fun anExistingFileIsReplacedOnlyWithReplace() {
        jotter("add", "milk")
        val copy = out.resolve("jotter.db")
        copy.writeText("keep me")
        val refused = jotter("export-database", copy.toString())
        assertEquals(1, refused.statusCode)
        assertContains(refused.stderr, "exists already, so it was left alone")
        assertContains(refused.stderr, "--replace")
        assertEquals("keep me", copy.readText())

        val replaced = jotter("export-database", "--replace", copy.toString())
        assertEquals(0, replaced.statusCode, replaced.stderr)
        assertContains(jotter("list", file = copy).stdout, "milk")
        assertEquals(listOf(copy), out.listDirectoryEntries())
    }

    @Test
    fun theLiveDatabaseIsNeverTheTarget() {
        jotter("add", "milk")
        val result = jotter("export-database", "--replace", database.toString())
        assertEquals(1, result.statusCode)
        assertContains(result.stderr, "That is the notes file Jotter is using")
        assertContains(jotter("list").stdout, "milk")
    }

    @Test
    fun failuresAreSaidInPlainWords() {
        jotter("add", "milk")
        val missing = jotter("export-database", directory.resolve("gone/jotter.db").toString())
        assertEquals(1, missing.statusCode)
        assertContains(missing.stderr, "does not exist. Choose another place")

        val noNotes = jotter("export-database", out.resolve("x.db").toString(), file = out.resolve("none.db"))
        assertEquals(1, noNotes.statusCode)
        assertContains(noNotes.stderr, "there are no notes to export")
        assertFalse(out.resolve("none.db").exists())
        assertFalse("Exception" in missing.stderr + noNotes.stderr)
    }

    @Test
    fun exportingWhileAnotherProcessWrites() {
        jotter("add", "the first note")
        val writer = startWriter()
        try {
            assertEquals(NoteWriter.READY, writer.inputStream.bufferedReader().readLine())
            val counts = mutableListOf<Int>()
            while (writer.isAlive || counts.isEmpty()) {
                val copy = out.resolve("copy.db")
                val result = jotter("export-database", "--replace", copy.toString())
                assertEquals(0, result.statusCode, result.stderr)
                counts += NoteStore.open(copy.toString(), "check", EmptyCoroutineContext).use {
                    it.notes.list().size
                }
            }
            assertEquals(0, writer.waitFor())
            println("ExportDatabaseCommandTest: copies with ${counts.distinct()} notes")
            assertEquals(counts.sorted(), counts)
            assertTrue(counts.any { it in 2..NOTES }, "no copy was made during the writes")
            assertEquals(listOf(out.resolve("copy.db")), out.listDirectoryEntries())
        } finally {
            writer.destroyForcibly()
        }
    }

    private fun startWriter(): Process =
        ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").path,
            "-cp",
            System.getProperty("java.class.path"),
            NoteWriter::class.java.name,
            database.toString(),
            NOTES.toString(),
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()

    private companion object {
        const val NOTES = 2_000
    }
}

/** Another process that writes notes, one transaction each, as the desktop app would. */
internal object NoteWriter {
    @JvmStatic
    fun main(args: Array<String>) {
        val (path, count) = args
        NoteStore.open(path, "writer", EmptyCoroutineContext).use { store ->
            println(READY)
            repeat(count.toInt()) { store.notes.create("Note $it", "from the other process") }
        }
    }

    const val READY = "ready"
}
