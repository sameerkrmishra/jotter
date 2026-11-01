package io.github.sameerkrmishra.jotter.core.storage

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.db.use
import io.github.sameerkrmishra.jotter.core.sqlite.openSqliteDriver
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class NoteStoreTest {
    @Test
    fun reopeningKeepsTheDeviceAndTheNotes() =
        withTempDir { dir ->
            val (deviceId, note) =
                openStore(dir.path).use { store ->
                    store.deviceId to store.notes.create("", "milk")
                }

            openStore(dir.path).use { store ->
                assertEquals(deviceId, store.deviceId)
                assertEquals(listOf(note), store.notes.list())
            }
            // Closing checkpointed the WAL: the file can be copied on its own.
            assertTrue(dir.exists())
            assertFalse(dir.exists("-wal"))
        }

    @Test
    fun byDefaultTheStoreFollowsTheSystemClock() =
        withTempDir { dir ->
            val before = Clock.System.now().toEpochMilliseconds()
            val note =
                NoteStore.open(dir.path, "Laptop", EmptyCoroutineContext).use { store ->
                    store.notes.create("", "milk")
                }
            val after = Clock.System.now().toEpochMilliseconds()

            assertTrue(note.createdAt.toEpochMilliseconds() in before..after)
        }

    @Test
    fun eachNewDatabaseGetsItsOwnDevice() =
        withStore { first ->
            withStore { second -> assertNotEquals(first.deviceId, second.deviceId) }
        }

    @Test
    fun aDatabaseLockedDuringOpenIsReportedAsBusy() =
        withTempDir { dir ->
            // In rollback mode, unlike WAL, a connection holding the file blocks even readers.
            val holder = BundledSQLiteDriver().open(dir.path)
            try {
                holder.execSQL("BEGIN EXCLUSIVE")
                holder.execSQL("CREATE TABLE being_created (x)")

                val error = assertFailsWith<StorageBusyException> { openStore(dir.path) }

                assertNotNull(error.cause)
                holder.execSQL("ROLLBACK")
            } finally {
                holder.close()
            }
            openStore(dir.path).use { }
        }

    @Test
    fun aFileWithAnotherApplicationIdIsRejectedAndLeftUntouched() =
        withTempDir { dir ->
            openSqliteDriver(dir.path, EmptySchemaVersion1, applicationId = 1).use { }
            val before = dir.bytes()

            val error = assertFailsWith<IllegalStateException> { openStore(dir.path) }

            assertContains(error.message.orEmpty(), "application id is 1,")
            assertEquals(before, dir.bytes())
        }

    @Test
    fun aSqliteFileWithTablesButNoVersionIsRejectedAndLeftUntouched() =
        withTempDir { dir ->
            // A mistyped path: a file of some other program, which uses neither pragma.
            openSqliteDriver(dir.path, EmptySchemaVersion1, applicationId = 1).use { other ->
                other.execute(null, "CREATE TABLE theirs(id INTEGER PRIMARY KEY)", 0)
                other.execute(null, "PRAGMA application_id = 0", 0)
                other.execute(null, "PRAGMA user_version = 0", 0)
            }
            val before = dir.bytes()

            assertFailsWith<IllegalStateException> { openStore(dir.path) }

            assertEquals(before, dir.bytes())
            assertFalse(dir.exists("-wal"))
        }

    @Test
    fun aDatabaseWithJottersIdButNoneOfItsTablesFailsAndIsClosed() =
        withTempDir { dir ->
            openSqliteDriver(dir.path, EmptySchemaVersion1, JOTTER_APPLICATION_ID).use { }

            val error = assertFailsWith<Exception> { openStore(dir.path) }
            assertContains(error.message.orEmpty(), "no such table: device")
            // The store closed its connection again, which removed the WAL.
            assertFalse(dir.exists("-wal"))
        }

    private object EmptySchemaVersion1 : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = 1

        override fun create(driver: SqlDriver): QueryResult.Value<Unit> = QueryResult.Unit

        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: AfterVersion,
        ): QueryResult.Value<Unit> = QueryResult.Unit
    }
}
