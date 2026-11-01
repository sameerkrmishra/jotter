package io.github.sameerkrmishra.jotter.core.sqlite

import androidx.sqlite.SQLiteException
import androidx.sqlite.execSQL
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
            assertFailsWith<IllegalStateException> {
                openSqliteDriver(":memory:", NotesSchema, TEST_APPLICATION_ID)
            }
        assertContains(error.message.orEmpty(), "journal_mode is memory")
    }

    @Test
    fun newDatabaseIsCreatedAndGetsTheSchemaVersion() =
        withTempDir { dir ->
            val schema = RecordingSchema(version = 3)
            openSqliteDriver(dir.path, schema, TEST_APPLICATION_ID).use { driver ->
                assertEquals(listOf("create"), schema.calls)
                assertEquals(3L, driver.queryLong("PRAGMA user_version"))
                assertEquals(0L, driver.queryLong("SELECT count(*) FROM item"))
            }
        }

    @Test
    fun reopeningAtTheSameVersionNeitherCreatesNorMigrates() =
        withTempDir { dir ->
            val schema = RecordingSchema(version = 2)
            openSqliteDriver(dir.path, schema, TEST_APPLICATION_ID).use { driver ->
                driver.execute(null, "INSERT INTO item DEFAULT VALUES", 0)
            }
            val reopened = RecordingSchema(version = 2)
            openSqliteDriver(dir.path, reopened, TEST_APPLICATION_ID).use { driver ->
                assertEquals(emptyList(), reopened.calls)
                assertEquals(1L, driver.queryLong("SELECT count(*) FROM item"))
            }
        }

    @Test
    fun olderDatabaseIsMigratedWithTheCallbacks() =
        withTempDir { dir ->
            openSqliteDriver(dir.path, RecordingSchema(version = 1), TEST_APPLICATION_ID).close()
            val schema = RecordingSchema(version = 3)
            val callback = AfterVersion(2) {}
            openSqliteDriver(dir.path, schema, TEST_APPLICATION_ID, callback).use { driver ->
                assertEquals(listOf("migrate 1 -> 3"), schema.calls)
                assertSame(callback, schema.callbacks.single())
                assertEquals(3L, driver.queryLong("PRAGMA user_version"))
            }
        }

    @Test
    fun newerDatabaseIsRejectedAndLeftUnchanged() =
        withTempDir { dir ->
            openSqliteDriver(dir.path, RecordingSchema(version = 3), TEST_APPLICATION_ID).close()
            val error =
                assertFailsWith<IllegalStateException> {
                    openSqliteDriver(dir.path, RecordingSchema(version = 2), TEST_APPLICATION_ID)
                }
            assertContains(error.message.orEmpty(), "schema version 3, newer than this app's 2")
            // The failed open closed its connection: the WAL files are gone again.
            assertFalse(dir.exists("-wal"))
            val schema = RecordingSchema(version = 3)
            openSqliteDriver(dir.path, schema, TEST_APPLICATION_ID).use { driver ->
                assertEquals(emptyList(), schema.calls)
                assertEquals(3L, driver.queryLong("PRAGMA user_version"))
            }
        }

    @Test
    fun failedCreateIsRolledBackSoTheNextOpenCreatesAgain() =
        withTempDir { dir ->
            assertFailsWith<SQLiteException> {
                openSqliteDriver(
                    dir.path,
                    RecordingSchema(version = 1, failCreate = true),
                    TEST_APPLICATION_ID,
                )
            }
            assertTrue(dir.exists())
            // Had the first CREATE TABLE survived, this create would fail with "already exists".
            val schema = RecordingSchema(version = 1)
            openSqliteDriver(dir.path, schema, TEST_APPLICATION_ID).use { driver ->
                assertEquals(listOf("create"), schema.calls)
                assertEquals(1L, driver.queryLong("PRAGMA user_version"))
            }
        }

    @Test
    fun newDatabaseGetsTheApplicationIdAndReopensWithIt() =
        withTempDir { dir ->
            openSqliteDriver(dir.path, NotesSchema, TEST_APPLICATION_ID).use { driver ->
                assertEquals(
                    TEST_APPLICATION_ID.toLong(),
                    driver.queryLong("PRAGMA application_id"),
                )
            }
            openSqliteDriver(dir.path, NotesSchema, TEST_APPLICATION_ID).close()
        }

    @Test
    fun applicationIdZeroIsNotAnId() =
        withTempDir { dir ->
            assertFailsWith<IllegalArgumentException> { openSqliteDriver(dir.path, NotesSchema, 0) }
            assertFalse(dir.exists())
        }

    @Test
    fun emptyDatabaseOfAnyOriginIsTreatedAsNew() =
        withTempDir { dir ->
            // VACUUM gives the file a valid header, as an empty database another tool created.
            val raw = openRawConnection(dir.path)
            raw.execSQL("VACUUM")
            raw.close()

            openSqliteDriver(dir.path, NotesSchema, TEST_APPLICATION_ID).use { driver ->
                assertEquals(1L, driver.queryLong("PRAGMA user_version"))
            }
        }

    @Test
    fun databaseWithAnotherApplicationIdIsRejectedAndLeftUntouched() =
        foreignFileIsRejected(
            "PRAGMA application_id = 1",
            "PRAGMA user_version = 1",
            "CREATE TABLE theirs(id INTEGER PRIMARY KEY)",
        )

    @Test
    fun emptyDatabaseWithAnotherApplicationIdIsRejectedAndLeftUntouched() =
        foreignFileIsRejected("PRAGMA application_id = 1")

    @Test
    fun tablesWithoutAnApplicationIdOrVersionAreRejectedAndLeftUntouched() =
        foreignFileIsRejected("CREATE TABLE theirs(id INTEGER PRIMARY KEY)")

    @Test
    fun tablesWithoutAnApplicationIdAtSomeVersionAreRejectedAndLeftUntouched() =
        foreignFileIsRejected(
            "PRAGMA user_version = 1",
            "CREATE TABLE theirs(id INTEGER PRIMARY KEY)",
        )

    @Test
    fun tablesWithOurApplicationIdButNoVersionAreRejectedAndLeftUntouched() =
        foreignFileIsRejected(
            "PRAGMA application_id = $TEST_APPLICATION_ID",
            "CREATE TABLE theirs(id INTEGER PRIMARY KEY)",
        )

    /** A file that plain SQLite set up with [setup], so still in its default journal mode. */
    private fun foreignFileIsRejected(vararg setup: String) =
        withTempDir { dir ->
            val raw = openRawConnection(dir.path)
            setup.forEach(raw::execSQL)
            raw.close()
            val before = dir.bytes()

            val schema = RecordingSchema(version = 1)
            val error =
                assertFailsWith<IllegalStateException> {
                    openSqliteDriver(dir.path, schema, TEST_APPLICATION_ID)
                }

            assertContains(error.message.orEmpty(), "Not a database of this application")
            assertEquals(emptyList(), schema.calls)
            assertEquals(before, dir.bytes())
            assertFalse(dir.exists("-wal"))
            assertFalse(dir.exists("-shm"))
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
