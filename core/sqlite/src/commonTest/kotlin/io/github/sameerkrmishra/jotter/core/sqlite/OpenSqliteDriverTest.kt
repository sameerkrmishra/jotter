package io.github.sameerkrmishra.jotter.core.sqlite

import androidx.sqlite.SQLiteException
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.db.use
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OpenSqliteDriverTest {
    @Test
    fun connectionPragmasTakeEffect() =
        withNotesDatabase { db ->
            val driver = db.sqlDriver
            assertEquals("wal", driver.queryText("PRAGMA journal_mode"))
            assertEquals(5000L, driver.queryLong("PRAGMA busy_timeout"))
            assertEquals(1L, driver.queryLong("PRAGMA foreign_keys"))
        }

    @Test
    fun foreignKeysAreEnforced() =
        withNotesDatabase { db ->
            val driver = db.sqlDriver
            driver.execute(
                null,
                "CREATE TABLE tag(note_id INTEGER NOT NULL REFERENCES note(id))",
                0,
            )
            val error =
                assertFailsWith<SQLiteException> {
                    driver.execute(null, "INSERT INTO tag(note_id) VALUES (42)", 0)
                }
            assertContains(error.message.orEmpty(), "FOREIGN KEY constraint failed")
        }

    @Test
    fun inMemoryDatabaseIsRejectedBecauseItCannotUseWal() {
        val error =
            assertFailsWith<IllegalStateException> { openSqliteDriver(":memory:", NotesSchema) }
        assertContains(error.message.orEmpty(), "journal_mode is memory")
    }

    @Test
    fun newDatabaseIsCreatedAndGetsTheSchemaVersion() =
        withTempDir { dir ->
            val schema = RecordingSchema(version = 3)
            openSqliteDriver(dir.path, schema).use { driver ->
                assertEquals(listOf("create"), schema.calls)
                assertEquals(3L, driver.queryLong("PRAGMA user_version"))
                assertEquals(0L, driver.queryLong("SELECT count(*) FROM item"))
            }
        }

    @Test
    fun reopeningAtTheSameVersionNeitherCreatesNorMigrates() =
        withTempDir { dir ->
            openSqliteDriver(dir.path, RecordingSchema(version = 2)).use { driver ->
                driver.execute(null, "INSERT INTO item DEFAULT VALUES", 0)
            }
            val schema = RecordingSchema(version = 2)
            openSqliteDriver(dir.path, schema).use { driver ->
                assertEquals(emptyList(), schema.calls)
                assertEquals(1L, driver.queryLong("SELECT count(*) FROM item"))
            }
        }

    @Test
    fun olderDatabaseIsMigratedWithTheCallbacks() =
        withTempDir { dir ->
            openSqliteDriver(dir.path, RecordingSchema(version = 1)).close()
            val schema = RecordingSchema(version = 3)
            val callback = AfterVersion(2) {}
            openSqliteDriver(dir.path, schema, callback).use { driver ->
                assertEquals(listOf("migrate 1 -> 3"), schema.calls)
                assertSame(callback, schema.callbacks.single())
                assertEquals(3L, driver.queryLong("PRAGMA user_version"))
            }
        }

    @Test
    fun newerDatabaseIsRejectedAndLeftUnchanged() =
        withTempDir { dir ->
            openSqliteDriver(dir.path, RecordingSchema(version = 3)).close()
            val error =
                assertFailsWith<IllegalStateException> {
                    openSqliteDriver(dir.path, RecordingSchema(version = 2))
                }
            assertContains(error.message.orEmpty(), "schema version 3, newer than this app's 2")
            // The failed open closed its connection: the WAL files are gone again.
            assertFalse(dir.exists("-wal"))
            val schema = RecordingSchema(version = 3)
            openSqliteDriver(dir.path, schema).use { driver ->
                assertEquals(emptyList(), schema.calls)
                assertEquals(3L, driver.queryLong("PRAGMA user_version"))
            }
        }

    @Test
    fun failedCreateIsRolledBackSoTheNextOpenCreatesAgain() =
        withTempDir { dir ->
            assertFailsWith<SQLiteException> {
                openSqliteDriver(dir.path, RecordingSchema(version = 1, failCreate = true))
            }
            assertTrue(dir.exists())
            // Had the first CREATE TABLE survived, this create would fail with "already exists".
            val schema = RecordingSchema(version = 1)
            openSqliteDriver(dir.path, schema).use { driver ->
                assertEquals(listOf("create"), schema.calls)
                assertEquals(1L, driver.queryLong("PRAGMA user_version"))
            }
        }

    /** Creates `item(id)`, optionally followed by an invalid statement; records every call. */
    private class RecordingSchema(
        override val version: Long,
        private val failCreate: Boolean = false,
    ) : SqlSchema<QueryResult.Value<Unit>> {
        val calls = mutableListOf<String>()
        val callbacks = mutableListOf<AfterVersion>()

        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            calls += "create"
            driver.execute(null, "CREATE TABLE item(id INTEGER PRIMARY KEY)", 0)
            if (failCreate) driver.execute(null, "CREATE TABLE broken(", 0)
            return QueryResult.Unit
        }

        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: AfterVersion,
        ): QueryResult.Value<Unit> {
            calls += "migrate $oldVersion -> $newVersion"
            this.callbacks += callbacks
            return QueryResult.Unit
        }
    }
}
