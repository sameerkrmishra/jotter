package io.github.sameerkrmishra.jotter.app.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.sameerkrmishra.jotter.app.sharedui.ExportStep
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * "Export database" on a device: the copy is made in the app's cache, then written to the document
 * the picker gave, here a file standing in for it. The picker itself is another app's screen.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("InjectDispatcher") // The test stands in for the Activity, which chooses dispatchers.
class DatabaseExportTest {
    private val test = TestGraph()
    private val out = File(test.folder, "out").apply { mkdirs() }
    private val staging = File(test.folder, "staging")

    @After
    fun cleanUp() {
        test.close()
    }

    /** A document that is the file [file], and remembers whether it was deleted. */
    private class FileDocument(
        val file: File,
        val failWith: IOException? = null,
    ) : ChosenDocument {
        var deleted = false

        override val name: String = file.name

        override fun open(): OutputStream {
            val stream = FileOutputStream(file)
            val failure = failWith ?: return stream
            return object : OutputStream() {
                override fun write(b: Int) = throw failure

                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) = throw failure

                override fun close() = stream.close()
            }
        }

        override fun delete() {
            deleted = true
            file.delete()
        }
    }

    private fun export(document: FileDocument?): ExportStep =
        runBlocking {
            exportThroughPicker(test.ready.export::export, staging, Dispatchers.IO) { document }
        }

    private fun open(file: File) = NoteStore.open(file.path, "check", EmptyCoroutineContext)

    @Test
    fun theCopyOpensAsARestoredDeviceWhileAnotherConnectionWrites() {
        val store = test.ready.store
        store.notes.create("Shopping", "milk")
        // The share target or the worker, writing at the same time on a connection of its own.
        val writing = AtomicBoolean(true)
        val writer =
            thread {
                open(store.path.let(::File)).use { other ->
                    var index = 0
                    while (writing.get()) other.notes.create("", "written meanwhile ${index++}")
                }
            }
        val copies =
            try {
                (1..EXPORTS).map { index ->
                    val document = FileDocument(File(out, "copy-$index.db"))
                    val step = assertIs<ExportStep.Done>(export(document))
                    assertContains(step.message, "Exported your notes to copy-$index.db")
                    assertContains(step.message, "Passwords and pairing tokens are not in it")
                    open(document.file).use { it.notes.list().size }
                }
            } finally {
                writing.set(false)
                writer.join()
            }
        assertEquals(copies.sorted(), copies)
        assertTrue(copies.all { it >= 1 })
        // Nothing stays in the app's cache.
        assertFalse(File(staging, DATABASE_FILE_NAME).exists())

        open(File(out, "copy-$EXPORTS.db")).use { restored ->
            assertEquals(store.deviceId, restored.deviceId)
            assertTrue(restored.notes.list().any { it.body == "milk" })
        }
    }

    @Test
    fun aCancelledPickerLeavesNothing() {
        test.ready.store.notes
            .create("", "milk")
        assertEquals(ExportStep.Cancelled, export(null))
        assertFalse(File(staging, DATABASE_FILE_NAME).exists())
        assertEquals(emptyList(), out.list().orEmpty().toList())
    }

    @Test
    fun aFullPlaceIsSaidInPlainWordsAndTheHalfCopyDeleted() {
        test.ready.store.notes
            .create("", "milk")
        val document = FileDocument(File(out, "copy.db"), IOException("No space left on device"))
        val step = assertIs<ExportStep.Failed>(export(document))
        assertEquals(
            "There is not enough free space in the place you chose for the copy. Free some " +
                "space or choose another place. Your notes have not been changed.",
            step.message,
        )
        assertTrue(document.deleted)
        assertFalse(document.file.exists())
        assertEquals(
            "milk",
            test.ready.store.notes
                .list()
                .single()
                .body,
        )
    }

    private companion object {
        const val EXPORTS = 5
    }
}
