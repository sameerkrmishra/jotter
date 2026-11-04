package io.github.sameerkrmishra.jotter.app.sync

import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** "Export database" against real processes and file permissions. */
class DatabaseExportJvmTest {
    private val files = FileSystem.SYSTEM

    @Test
    fun exportingWhileAnotherProcessWritesGivesWholeCopies() =
        runTest {
            TempDir().use { dir ->
                Device(dir, "app").use { app ->
                    app.store.notes.create("", "the app's own note")
                    val writer = startWriter(app.file)
                    try {
                        val fromWriter = writer.inputStream.bufferedReader()
                        assertEquals(NoteWriterProcess.READY, fromWriter.readLine())
                        val export = DatabaseExport(app.store, EmptyCoroutineContext)
                        // Exports one after another for as long as the other process writes.
                        val seen = mutableListOf<Int>()
                        while (writer.isAlive || seen.isEmpty()) {
                            val file = dir.folder("out") / "copy-${seen.size}.db"
                            assertIs<ExportOutcome.Exported>(export.export(file))
                            seen +=
                                NoteStore.open(file.toString(), "check", EmptyCoroutineContext).use {
                                    it.notes.list().size
                                }
                            files.delete(file)
                        }
                        assertEquals(NoteWriterProcess.DONE, fromWriter.readLine())
                        assertEquals(0, writer.waitFor())
                        println("DatabaseExportJvmTest: ${seen.size} copies, ${seen.distinct()}")
                        // Each copy is a moment of the database: never fewer notes than before,
                        // and some were taken while the other process was half way.
                        assertEquals(seen.sorted(), seen)
                        assertTrue(seen.all { it in 1..1 + NOTES })
                        assertTrue(seen.any { it in 2..NOTES }, "no copy was made during the writes")
                        val last = dir.folder("out") / "last.db"
                        assertIs<ExportOutcome.Exported>(export.export(last))
                        NoteStore.open(last.toString(), "check", EmptyCoroutineContext).use {
                            assertEquals(1 + NOTES, it.notes.list().size)
                        }
                    } finally {
                        writer.destroyForcibly()
                    }
                }
            }
        }

    @Test
    fun aFolderWithoutWritePermissionIsSaidSoAndKeepsNothing() =
        runTest {
            TempDir().use { dir ->
                val locked = dir.folder("locked")
                val folder = locked.toNioPath()
                Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-xr-xr-x"))
                try {
                    // Root may write anyway: then there is nothing to test.
                    if (Files.isWritable(folder)) return@runTest
                    Device(dir, "a").use { device ->
                        device.store.notes.create("", "milk")
                        val outcome =
                            DatabaseExport(device.store, EmptyCoroutineContext)
                                .export(locked / "jotter.db")
                        val said = assertIs<ExportOutcome.Failed>(outcome).message
                        assertContains(said, "not allowed to write in $locked")
                        assertEquals(emptyList(), files.list(locked))
                        assertEquals("milk", device.store.notes.list().single().body)
                    }
                } finally {
                    Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"))
                }
            }
        }

    private fun startWriter(database: String): Process {
        val java = File(System.getProperty("java.home"), "bin/java").path
        return ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            NoteWriterProcess::class.java.name,
            database,
            NOTES.toString(),
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    }

    private companion object {
        const val NOTES = 2_000
    }
}
