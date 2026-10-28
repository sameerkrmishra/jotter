package io.github.sameerkrmishra.jotter.core.storage

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.db.use
import io.github.sameerkrmishra.jotter.core.sqlite.openSqliteDriver
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
    fun aDatabaseThatIsNotJottersIsRejectedAndClosed() =
        withTempDir { dir ->
            // Schema version 1, as Jotter's, but none of its tables.
            openSqliteDriver(dir.path, EmptySchemaVersion1).use { }

            assertFailsWith<Exception> { openStore(dir.path) }
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
