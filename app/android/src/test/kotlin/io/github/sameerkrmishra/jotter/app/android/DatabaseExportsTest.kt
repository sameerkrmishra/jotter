package io.github.sameerkrmishra.jotter.app.android

import io.github.sameerkrmishra.jotter.app.sharedui.DatabaseExportPlace
import io.github.sameerkrmishra.jotter.app.sharedui.ExportStep
import io.github.sameerkrmishra.jotter.app.sync.ExportOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okio.Path
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * "Export database" through the document picker, with a stand-in for the copy, which needs the
 * device's SQLite (`DatabaseExportTest` on a device makes real ones), and a file for the document.
 */
class DatabaseExportsTest {
    private val folder = Files.createTempDirectory("jotter-android-export").toFile()
    private val staging = File(folder, "staging")
    private val staged = File(staging, DATABASE_FILE_NAME)
    private val target = File(folder, "picked.db")
    private val written = mutableListOf<Boolean>()

    @AfterTest
    fun cleanUp() {
        folder.deleteRecursively()
    }

    /** Writes [bytes] as the copy, as `DatabaseExport.export` would, and remembers `replace`. */
    private fun copy(bytes: String = "a copy"): suspend (Path, Boolean) -> ExportOutcome =
        { file, replace ->
            written += replace
            file.toFile().writeText(bytes)
            ExportOutcome.Exported(file, bytes.length.toLong(), "said by DatabaseExport")
        }

    private class Document(
        val file: File,
        val openFails: Exception? = null,
        val deleteFails: Exception? = null,
    ) : ChosenDocument {
        var deleted = false
        override val name: String = file.name

        override fun open(): OutputStream {
            openFails?.let { throw it }
            return FileOutputStream(file)
        }

        override fun delete() {
            deleted = true
            deleteFails?.let { throw it }
            file.delete()
        }
    }

    @Test
    fun theCheckedCopyGoesToThePickedDocumentAndNothingStays() =
        runTest {
            val document = Document(target)
            val step =
                exportThroughPicker(copy("SQLite format 3"), staging, EmptyCoroutineContext) {
                    // The copy is made before the picker shows.
                    assertTrue(staged.exists())
                    document
                }
            val done = assertIs<ExportStep.Done>(step)
            assertEquals(
                "Exported your notes to picked.db. Passwords and pairing tokens are not in it: " +
                    "after restoring it, enter them again.",
                done.message,
            )
            assertEquals("SQLite format 3", target.readText())
            assertEquals(listOf(true), written)
            assertFalse(staged.exists())
            assertFalse(document.deleted)
        }

    @Test
    fun aDocumentThatIsNoFileIsWrittenToo() =
        runTest {
            val bytes = java.io.ByteArrayOutputStream()
            val stream =
                object : ChosenDocument {
                    override val name = "cloud.db"

                    override fun open(): OutputStream = bytes

                    override fun delete() = error("not deleted")
                }
            val step =
                exportThroughPicker(copy("SQLite"), staging, EmptyCoroutineContext) { stream }
            assertIs<ExportStep.Done>(step)
            assertEquals("SQLite", bytes.toString())
        }

    @Test
    fun aCopyThatFailsIsSaidBeforeThePickerShows() =
        runTest {
            var asked = false
            val step =
                exportThroughPicker(
                    { _, _ -> ExportOutcome.Failed("There is not enough free space.") },
                    staging,
                    EmptyCoroutineContext,
                ) {
                    asked = true
                    Document(target)
                }
            assertEquals(ExportStep.Failed("There is not enough free space."), step)
            assertFalse(asked)
            assertFalse(target.exists())
        }

    @Test
    fun aCancelledPickerWritesNothingAndDeletesTheCopy() =
        runTest {
            val step = exportThroughPicker(copy(), staging, EmptyCoroutineContext) { null }
            assertEquals(ExportStep.Cancelled, step)
            assertFalse(staged.exists())
            assertFalse(target.exists())
        }

    @Test
    fun aDocumentThatCannotBeWrittenIsDeletedAndSaidPlainly() =
        runTest {
            val document = Document(target, openFails = IOException("No space left on device"))
            val step = exportThroughPicker(copy(), staging, EmptyCoroutineContext) { document }
            val failed = assertIs<ExportStep.Failed>(step)
            assertContains(failed.message, "not enough free space in the place you chose")
            assertContains(failed.message, "Your notes have not been changed.")
            assertTrue(document.deleted)
            assertFalse(staged.exists())
        }

    @Test
    fun aDocumentThatCannotBeDeletedEitherStillEndsInPlainWords() =
        runTest {
            val document =
                Document(
                    target,
                    openFails = SecurityException("Permission Denial"),
                    deleteFails = IllegalStateException("gone"),
                )
            val step = exportThroughPicker(copy(), staging, EmptyCoroutineContext) { document }
            assertContains(assertIs<ExportStep.Failed>(step).message, "the place you chose")
            assertTrue(document.deleted)
        }

    @Test
    fun aCancelledWriteDeletesTheDocument() =
        runTest {
            val document = Document(target, openFails = CancellationException("left"))
            assertFailsWith<CancellationException> {
                exportThroughPicker(copy(), staging, EmptyCoroutineContext) { document }
            }
            assertTrue(document.deleted)
            assertFalse(staged.exists())
        }

    @Test
    fun aStagedCopyIsNeverRefusedAsExisting() =
        runTest {
            assertFailsWith<IllegalStateException> {
                exportThroughPicker(
                    { f, _ -> ExportOutcome.Exists(f) },
                    staging,
                    EmptyCoroutineContext,
                ) { Document(target) }
            }
        }

    @Test
    fun anExportGoesToTheScreenOnTop() =
        runTest {
            val export = ScreenDatabaseExport()
            assertEquals(ExportStep.Failed(NO_SCREEN_TO_EXPORT), export.export())
            export.screen = DatabaseExportPlace { ExportStep.Cancelled }
            assertEquals(ExportStep.Cancelled, export.export())
        }
}
