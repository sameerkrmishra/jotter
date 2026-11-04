package io.github.sameerkrmishra.jotter.core.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.use
import io.github.sameerkrmishra.jotter.core.sqlite.openSqliteDriver
import io.github.sameerkrmishra.jotter.core.storage.db.JotterDatabase
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** "Export database": `VACUUM INTO` a single file that is a whole, sound copy (ADR-0002). */
class ExportTest {
    @Test
    fun theCopyHoldsEveryTableAndTheLiveFileIsNotTouched() =
        withTempDir { dir ->
            val copy = "${dir.path}.copy"
            openStore(dir.path).use { store ->
                val kept = store.notes.create("Shopping", "milk and bread")
                store.notes.create("", "second")
                store.notes.edit(kept.id, kept.revisionId, "Shopping", "milk, bread and tea")
                store.backends.add(
                    BackendRecord("stick", "folder", "Stick", """{"path":"/media/stick"}""", T0),
                )
                // The writes are still in the WAL, which only the copy may read.
                assertTrue(dir.exists("-wal"))
                val before = dir.bytes()
                val walBefore =
                    FileSystem.SYSTEM.read(
                        "${dir.path}-wal".toPath(),
                    ) { readByteString() }

                store.exportTo(copy)

                assertEquals(before, dir.bytes())
                assertEquals(
                    walBefore,
                    FileSystem.SYSTEM.read("${dir.path}-wal".toPath()) { readByteString() },
                )
                // Every table, the sync tables too, has the same rows.
                val rows = counts(copy)
                assertTrue(rows.getValue("backend") == 1L && rows.getValue("revision") == 3L)
                assertEquals(counts(dir.path), rows)
                // The live store goes on as before.
                store.notes.create("", "after the export")
            }
            // One file, nothing beside it.
            assertFalse(FileSystem.SYSTEM.exists("$copy-wal".toPath()))
            assertFalse(FileSystem.SYSTEM.exists("$copy-journal".toPath()))

            val live = openStore(dir.path).use { it.deviceId to it.notes.list().size }
            openStore(copy).use { restored ->
                // A restored device keeps its id (ADR-0014).
                assertEquals(live.first, restored.deviceId)
                assertEquals(2, restored.notes.list().size)
                assertEquals(
                    "milk, bread and tea",
                    restored.notes
                        .search("tea")
                        .single()
                        .note.body,
                )
                assertEquals(listOf("stick"), restored.backends.list().map { it.id })
            }
            assertEquals(3, live.second)
        }

    @Test
    fun anExistingFileIsNeitherOverwrittenNorAccepted() =
        withTempDir { dir ->
            val copy = "${dir.path}.copy".toPath()
            FileSystem.SYSTEM.write(copy) { writeUtf8("someone's file") }
            openStore(dir.path).use { store ->
                store.notes.create("", "milk")
                assertFailsWith<RuntimeException> { store.exportTo(copy.toString()) }
                store.notes.create("", "still writable")
            }
            assertEquals("someone's file", FileSystem.SYSTEM.read(copy) { readUtf8() })
        }

    @Test
    fun aFolderThatDoesNotExistIsNoAccess() =
        withTempDir { dir ->
            openStore(dir.path).use { store ->
                val failure =
                    assertFailsWith<RuntimeException> {
                        store.exportTo("${dir.path}.missing/copy.db")
                    }
                assertEquals(StorageProblem.NO_ACCESS, storageProblemOf(failure))
            }
        }

    /** The number of rows in each of the database's tables, opened as Jotter opens it. */
    private fun counts(path: String): Map<String, Long> =
        openSqliteDriver(path, JotterDatabase.Schema, JOTTER_APPLICATION_ID).use { driver ->
            val tables =
                driver
                    .executeQuery(
                        null,
                        "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
                        { cursor ->
                            QueryResult.Value(
                                buildList {
                                    while (cursor.next().value) {
                                        add(
                                            cursor.getString(0)!!,
                                        )
                                    }
                                },
                            )
                        },
                        0,
                    ).value
            tables.associateWith { driver.queryLong("SELECT count(*) FROM \"$it\"") ?: 0 }
        }

    private companion object {
        val T0: Instant = Instant.fromEpochMilliseconds(0)
    }
}
