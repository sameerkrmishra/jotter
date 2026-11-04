package io.github.sameerkrmishra.jotter.app.desktop

import io.github.sameerkrmishra.jotter.app.sharedui.ExportStep
import io.github.sameerkrmishra.jotter.app.sync.DatabaseExport
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** "Export database" from the desktop's File menu, to the file its save dialog gives. */
@OptIn(ExperimentalPathApi::class)
class DatabaseFileTest {
    private val directory = createTempDirectory("jotter-desktop-export")
    private val out = directory.resolve("out").also { it.toFile().mkdir() }
    private val file = out.resolve(DATABASE_FILE_NAME)
    private val store = NoteStore.open(directory.resolve("jotter.db").toString(), "PC", EmptyCoroutineContext)

    @AfterTest
    fun deleteEverything() {
        store.close()
        directory.deleteRecursively()
    }

    private fun place(chosen: Path?) = DatabaseFile({ chosen }, DatabaseExport(store, EmptyCoroutineContext))

    private fun notesIn(copy: Path) =
        NoteStore.open(copy.toString(), "check", EmptyCoroutineContext).use { copied ->
            copied.notes.list().map { it.body }
        }

    @Test
    fun theChosenFileGetsACopyThatOpens() =
        runBlocking {
            store.notes.create("", "milk")
            val step = assertIs<ExportStep.Done>(place(file).export())
            assertContains(step.message, "Exported your notes to $file")
            assertContains(step.message, "Passwords and pairing tokens are not in it")
            assertEquals(listOf("milk"), notesIn(file))
        }

    @Test
    fun aCancelledChoiceWritesNothing() =
        runBlocking {
            assertEquals(ExportStep.Cancelled, place(null).export())
            assertEquals(emptyList(), out.listDirectoryEntries())
        }

    @Test
    fun anExistingFileIsReplacedOnlyOnceTheUserSaysSo() =
        runBlocking {
            store.notes.create("", "milk")
            file.writeText("keep")
            val asked = assertIs<ExportStep.Exists>(place(file).export())
            assertEquals(DATABASE_FILE_NAME, asked.name)
            assertEquals("keep", file.readText())
            assertIs<ExportStep.Done>(asked.replace())
            assertEquals(listOf("milk"), notesIn(file))
            assertEquals(listOf(file), out.listDirectoryEntries())
        }
}
