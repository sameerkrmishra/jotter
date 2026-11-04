package io.github.sameerkrmishra.jotter.app.sync

import androidx.sqlite.SQLiteException
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okio.FileHandle
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** "Export database" in `app:sync`: the copy, where it is written, and what is said. */
class DatabaseExportTest {
    private val files = FileSystem.SYSTEM

    private fun test(block: suspend TempDir.() -> Unit) = runTest { TempDir().use { it.block() } }

    private fun Device.export() = DatabaseExport(store, EmptyCoroutineContext)

    /** The names in [folder], to see that no part of a copy was left there. */
    private fun names(folder: Path) = files.list(folder).map { it.name }.sorted()

    @Test
    fun theCopyIsOneSoundFileAndSaysThatSecretsAreNotInIt() =
        test {
            val out = folder("out")
            Device(this, "a").use { device ->
                device.store.notes.create("Shopping", "milk")
                val outcome = device.export().export(out / "jotter.db")
                val exported = assertIs<ExportOutcome.Exported>(outcome)
                assertEquals(out / "jotter.db", exported.file)
                assertEquals(files.metadata(exported.file).size, exported.bytes)
                assertContains(exported.message, "Passwords and pairing tokens are not in it")
                assertEquals(listOf("jotter.db"), names(out))
                // Only the database: a copy opens on its own, as Jotter's.
                NoteStore.open(exported.file.toString(), "other", EmptyCoroutineContext).use {
                    assertEquals(device.store.deviceId, it.deviceId)
                    assertEquals(
                        "milk",
                        it.notes
                            .list()
                            .single()
                            .body,
                    )
                }
            }
            assertContains(EXPORT_DATABASE_EXPLAINED, "Passwords and pairing tokens are not in it")
        }

    @Test
    fun anExistingFileIsReplacedOnlyWhenTheUserSaysSo() =
        test {
            val out = folder("out")
            val file = out / "jotter.db"
            files.write(file) { writeUtf8("an older copy") }
            Device(this, "a").use { device ->
                device.store.notes.create("", "milk")
                assertEquals(ExportOutcome.Exists(file), device.export().export(file))
                assertEquals("an older copy", files.read(file) { readUtf8() })
                assertEquals(listOf("jotter.db"), names(out))

                assertIs<ExportOutcome.Exported>(device.export().export(file, replace = true))
                assertEquals(listOf("jotter.db"), names(out))
                NoteStore.open(file.toString(), "other", EmptyCoroutineContext).use {
                    assertEquals(
                        "milk",
                        it.notes
                            .list()
                            .single()
                            .body,
                    )
                }
            }
        }

    @Test
    fun theLiveDatabaseAndItsSideFilesAreNeverATarget() =
        test {
            Device(this, "a").use { device ->
                device.store.notes.create("", "milk")
                val live = device.file.toPath()
                val before = files.read(live) { readByteString() }
                listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
                    val target = "$live$suffix".toPath()
                    assertEquals(
                        ExportOutcome.Failed(LIVE_DATABASE),
                        device.export().export(target, replace = true),
                    )
                }
                // Also by another spelling of the same path.
                val other = live.parent!! / "." / live.name
                assertEquals(
                    ExportOutcome.Failed(LIVE_DATABASE),
                    device.export().export(other, replace = true),
                )
                assertEquals(before, files.read(live) { readByteString() })
                assertEquals(
                    "milk",
                    device.store.notes
                        .list()
                        .single()
                        .body,
                )
            }
        }

    /** The real file system, but [move] and [delete] may fail, and [opened] runs on each open. */
    private class Faulty(
        val move: IOException? = null,
        val delete: IOException? = null,
        val opened: (Path) -> Unit = {},
    ) : ForwardingFileSystem(FileSystem.SYSTEM) {
        override fun openReadWrite(
            file: Path,
            mustCreate: Boolean,
            mustExist: Boolean,
        ): FileHandle {
            opened(file)
            return super.openReadWrite(file, mustCreate, mustExist)
        }

        override fun atomicMove(
            source: Path,
            target: Path,
        ) {
            move?.let { throw it }
            super.atomicMove(source, target)
        }

        override fun delete(
            path: Path,
            mustExist: Boolean,
        ) {
            delete?.let { throw it }
            super.delete(path, mustExist)
        }
    }

    @Test
    fun aFileThatAppearsWhileTheCopyIsMadeIsLeftAlone() =
        test {
            val out = folder("out")
            val file = out / "jotter.db"
            val appears = Faulty(opened = { files.write(file) { writeUtf8("theirs") } })
            Device(this, "a").use { device ->
                val export = DatabaseExport(device.store, EmptyCoroutineContext, appears)
                assertEquals(ExportOutcome.Exists(file), export.export(file))
                assertEquals("theirs", files.read(file) { readUtf8() })
                assertEquals(listOf("jotter.db"), names(out))
            }
        }

    @Test
    fun aFullDiskAtTheRenameLeavesNoPartAndSaysSo() =
        test {
            val out = folder("out")
            val full = Faulty(move = IOException("No space left on device"))
            Device(this, "a").use { device ->
                val outcome =
                    DatabaseExport(device.store, EmptyCoroutineContext, full) { "the stick" }
                        .export(out / "jotter.db")
                assertEquals(
                    "There is not enough free space in the stick for the copy. Free some space " +
                        "or choose another place. Your notes have not been changed.",
                    assertIs<ExportOutcome.Failed>(outcome).message,
                )
                assertEquals(emptyList(), names(out))
            }
        }

    @Test
    fun aPartThatCannotBeRemovedStillEndsInPlainWords() =
        test {
            val out = folder("out")
            val stuck =
                Faulty(
                    move = IOException("Input/output error"),
                    delete = IOException("Read-only file system"),
                )
            Device(this, "a").use { device ->
                val outcome =
                    DatabaseExport(device.store, EmptyCoroutineContext, stuck).export(out / "x.db")
                assertContains(assertIs<ExportOutcome.Failed>(outcome).message, "plugged in")
                // The part is hidden and named so; nothing has the name the user chose.
                val left = names(out)
                assertTrue(left.single().startsWith(".x.db.") && left.single().endsWith(".part"))
            }
        }

    @Test
    fun aCancelledExportLeavesNoPart() =
        test {
            val out = folder("out")
            val stops = Faulty(opened = { throw CancellationException("the app closes") })
            Device(this, "a").use { device ->
                assertFailsWith<CancellationException> {
                    DatabaseExport(device.store, EmptyCoroutineContext, stops).export(out / "x.db")
                }
                assertEquals(emptyList(), names(out))
            }
        }

    @Test
    fun aMissingFolderOrARelativePathIsRefusedInPlainWords() =
        test {
            Device(this, "a").use { device ->
                val missing = device.export().export(path / "gone" / "jotter.db")
                assertContains(assertIs<ExportOutcome.Failed>(missing).message, "does not exist")
                val relative = device.export().export("jotter.db".toPath())
                assertContains(assertIs<ExportOutcome.Failed>(relative).message, "not a full path")
                val folder = device.export().export(folder("out"))
                assertContains(assertIs<ExportOutcome.Failed>(folder).message, "is a folder")
                val root = device.export().export("/".toPath())
                assertContains(assertIs<ExportOutcome.Failed>(root).message, "not a full path")
                // A file where the folder should be.
                files.write(path / "plain") { writeUtf8("not a folder") }
                val underFile = device.export().export(path / "plain" / "jotter.db")
                assertContains(assertIs<ExportOutcome.Failed>(underFile).message, "does not exist")
                assertEquals(emptyList(), names(path / "out"))
            }
        }

    @Test
    fun aRestoredCopyIsARestoredDeviceThatGetsItsLostTailBack() =
        test {
            val stick = folder("stick")
            val copy = folder("out") / "backup.db"
            Device(this, "a").use { device ->
                device.manager.addLocalFolder(stick.toString(), setUpNewStore = true)
                device.store.notes.create("", "before the backup")
                device.manager.syncAll()
                assertIs<ExportOutcome.Exported>(device.export().export(copy))
                device.store.notes.create("", "after the backup")
                device.manager.syncAll()
            }
            // The disk is lost; the copy comes back in its place.
            val live = path / "db-a" / "a.db"
            files.delete(live)
            files.copy(copy, live)
            Device(this, "a").use { device ->
                assertEquals(
                    listOf("before the backup"),
                    device.store.notes
                        .list()
                        .map { it.body },
                )
                // Its backend is still there, and what it wrote after the backup comes back.
                assertEquals(
                    listOf(stick.toString()),
                    device.manager.list().map { it.config.location },
                )
                device.manager.syncAll()
                assertEquals(
                    setOf("before the backup", "after the backup"),
                    device.store.notes
                        .list()
                        .map { it.body }
                        .toSet(),
                )
                device.store.notes.create("", "after the restore")
                device.manager.syncAll()
            }
            Device(this, "b").use { other ->
                other.manager.addLocalFolder(stick.toString())
                other.manager.syncAll()
                assertEquals(
                    setOf("before the backup", "after the backup", "after the restore"),
                    other.store.notes
                        .list()
                        .map { it.body }
                        .toSet(),
                )
            }
        }

    @Test
    fun failuresSayWhatToDoAndThatTheNotesDidNotChange() {
        val folder = "/media/stick"
        val cases =
            mapOf(
                SQLiteException("Error code: 13, message: database or disk is full") to
                    "not enough free space in /media/stick",
                IOException("No space left on device") to "not enough free space",
                SQLiteException("Error code: 14, message: unable to open database file") to
                    "not allowed to write in /media/stick",
                IOException("/media/stick/x: Permission denied") to "not allowed to write",
                IOException("Read-only file system") to "not allowed to write",
                SQLiteException("Error code: 10, message: disk I/O error") to "plugged in",
                IllegalStateException("The copy failed its integrity check") to "debug log",
                IOException("Disk quota exceeded") to "not enough free space",
                IOException("AccessDeniedException /media/stick") to "not allowed to write",
                SQLiteException("Error code: 5, message: database is locked") to "Try again",
                SQLiteException("Error code: 8, message: readonly") to "not allowed to write",
            )
        cases.forEach { (failure, words) ->
            val said = describeExportFailure(failure, folder)
            assertContains(said, words)
            assertContains(said, "Your notes have not been changed.")
            assertFalse("Error code" in said || "message:" in said, said)
        }
        assertTrue(describeExportFailure(IOException("x"), folder).contains("plugged in"))
    }
}
