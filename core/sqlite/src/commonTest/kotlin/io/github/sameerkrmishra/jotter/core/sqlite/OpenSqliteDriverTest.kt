package io.github.sameerkrmishra.jotter.core.sqlite

import androidx.sqlite.SQLiteException
import androidx.sqlite.execSQL
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.db.use
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

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
    fun newerDatabaseInRollbackModeIsRejectedBeforeWalRewritesItsHeader() =
        rejectedAndLeftUntouched(
            "schema version 3, newer than this app's 1",
            "PRAGMA application_id = $TEST_APPLICATION_ID",
            "PRAGMA user_version = 3",
            "CREATE TABLE item(id INTEGER PRIMARY KEY)",
        )

    @Test
    fun aDatabaseUpgradedAfterTheFirstCheckIsRejectedToo() =
        withTempDir { dir ->
            openSqliteDriver(dir.path, RecordingSchema(version = 3), TEST_APPLICATION_ID).close()
            val schema = RecordingSchema(version = 2)
            openConnectionDriver(dir.path).use { driver ->
                val error =
                    assertFailsWith<IllegalStateException> {
                        createOrMigrate(driver, schema, TEST_APPLICATION_ID, emptyArray())
                    }
                assertContains(error.message.orEmpty(), "newer than this app's 2")
            }
            assertEquals(emptyList(), schema.calls)
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

    @Test
    fun rebuildingAReferencedTableKeepsTheChildRows() =
        withTempDir { dir ->
            openSqliteDriver(dir.path, ParentChildSchema(), TEST_APPLICATION_ID).close()

            val rebuild =
                ParentChildSchema(
                    version = 2,
                    migration =
                        listOf(
                            "CREATE TABLE parent_new(id INTEGER PRIMARY KEY, name TEXT)",
                            "INSERT INTO parent_new(id) SELECT id FROM parent",
                            "DROP TABLE parent",
                            "ALTER TABLE parent_new RENAME TO parent",
                        ),
                )
            openSqliteDriver(dir.path, rebuild, TEST_APPLICATION_ID).use { driver ->
                // Foreign keys are on again, and the child still points at the rebuilt table.
                assertEquals(1L, driver.queryLong("PRAGMA foreign_keys"))
                assertFailsWith<SQLiteException> {
                    driver.execute(null, "INSERT INTO child(parent_id) VALUES (42)", 0)
                }
                assertEquals(2L, driver.queryLong("PRAGMA user_version"))
                assertEquals(1L, driver.queryLong("SELECT count(*) FROM child"))
                assertEquals(1L, driver.queryLong("SELECT count(*) FROM parent"))
            }
        }

    @Test
    fun aMigrationLeavingADanglingReferenceIsRolledBack() =
        withTempDir { dir ->
            openSqliteDriver(dir.path, ParentChildSchema(), TEST_APPLICATION_ID).close()

            val dangling =
                ParentChildSchema(version = 2, migration = listOf("DELETE FROM parent"))
            val error =
                assertFailsWith<IllegalStateException> {
                    openSqliteDriver(dir.path, dangling, TEST_APPLICATION_ID)
                }

            assertContains(error.message.orEmpty(), "dangling foreign key: child -> parent")
            openSqliteDriver(dir.path, ParentChildSchema(), TEST_APPLICATION_ID).use { driver ->
                assertEquals(1L, driver.queryLong("PRAGMA user_version"))
                assertEquals(1L, driver.queryLong("SELECT count(*) FROM parent"))
                assertEquals(1L, driver.queryLong("SELECT count(*) FROM child"))
            }
        }

    @Test
    fun foreignKeysAreOnAgainAfterAFailedMigration() =
        withTempDir { dir ->
            openSqliteDriver(dir.path, ParentChildSchema(), TEST_APPLICATION_ID).close()

            val broken = ParentChildSchema(version = 2, migration = listOf("DROP TABLE missing"))
            openConnectionDriver(dir.path).use { driver ->
                assertFailsWith<SQLiteException> {
                    createOrMigrate(driver, broken, TEST_APPLICATION_ID, emptyArray())
                }
                assertEquals(1L, driver.queryLong("PRAGMA foreign_keys"))
                assertEquals(1L, driver.queryLong("PRAGMA user_version"))
            }
        }

    @Test
    fun aCreateRunsWithForeignKeysOn() =
        withTempDir { dir ->
            val schema = RecordingSchema(version = 1)
            openSqliteDriver(dir.path, schema, TEST_APPLICATION_ID).close()
            assertEquals(listOf<Long?>(1L), schema.foreignKeysSeen)
        }

    @Test
    fun aCreateFinishedByAnotherProcessMeanwhileIsNotRepeated() =
        withTempDir { dir ->
            val second = RecordingSchema(version = 1)
            lateinit var secondOpen: Deferred<Unit>
            val first =
                RecordingSchema(version = 1, onWrite = {
                    // Holds the write lock: the other open reads version 0 and then waits for it.
                    secondOpen = openInBackground(dir, second)
                    blockFor(HOLD)
                })
            openSqliteDriver(dir.path, first, TEST_APPLICATION_ID).close()

            runBlocking { secondOpen.await() }
            assertEquals(listOf("create"), first.calls)
            assertEquals(emptyList(), second.calls)
        }

    @Test
    fun aMigrationFinishedByAnotherProcessMeanwhileIsNotRepeated() =
        withTempDir { dir ->
            openSqliteDriver(dir.path, RecordingSchema(version = 1), TEST_APPLICATION_ID).close()

            val second = RecordingSchema(version = 2)
            lateinit var secondOpen: Deferred<Unit>
            val first =
                RecordingSchema(version = 2, onWrite = {
                    // Holds the write lock: the other open reads version 1 and then waits for it.
                    secondOpen = openInBackground(dir, second)
                    blockFor(HOLD)
                })
            openSqliteDriver(dir.path, first, TEST_APPLICATION_ID).use { driver ->
                runBlocking { secondOpen.await() }
                assertEquals(listOf("migrate 1 -> 2"), first.calls)
                assertEquals(emptyList(), second.calls)
                assertEquals(2L, driver.queryLong("PRAGMA user_version"))
            }
        }

    @Test
    fun concurrentOpensOfANewFileAllSucceed() {
        repeat(CONCURRENT_OPEN_ROUNDS) {
            withTempDir { dir ->
                runBlocking {
                    val start = CompletableDeferred<Unit>()
                    val opens =
                        List(2) {
                            async(testThreads) {
                                start.await()
                                openSqliteDriver(dir.path, NotesSchema, TEST_APPLICATION_ID).close()
                            }
                        }
                    start.complete(Unit)
                    opens.awaitAll()
                }
            }
        }
    }

    @Test
    fun aSwitchToWalThatStaysBusyFailsAfterTheTimeoutAndChangesNothing() =
        withTempDir { dir ->
            val reader = openRawConnection(dir.path)
            try {
                reader.execSQL("PRAGMA application_id = $TEST_APPLICATION_ID")
                reader.execSQL("PRAGMA user_version = 1")
                reader.execSQL("CREATE TABLE note(id INTEGER PRIMARY KEY)")
                val before = dir.bytes()
                // An open read transaction: its shared lock keeps WAL's exclusive lock out.
                reader.execSQL("BEGIN")
                reader.prepare("SELECT count(*) FROM note").use { it.step() }

                val start = TimeSource.Monotonic.markNow()
                val error =
                    assertFailsWith<SQLiteException> {
                        openSqliteDriver(dir.path, NotesSchema, TEST_APPLICATION_ID)
                    }

                assertTrue(isSqliteBusy(error), "Not busy: ${error.message}")
                val waited = start.elapsedNow()
                assertTrue(waited >= BUSY_TIMEOUT_MILLIS.milliseconds, "Gave up after $waited")
                assertEquals(before, dir.bytes())
            } finally {
                reader.close()
            }
        }

    @Test
    fun aSwitchToWalThatFailsForAnotherReasonFailsAtOnce() =
        withTempDir { dir ->
            val start = TimeSource.Monotonic.markNow()
            val error =
                assertFailsWith<SQLiteException> {
                    // SQLite refuses to change the journal mode inside a transaction.
                    openConnectionDriver(dir.path) { connection -> connection.execSQL("BEGIN") }
                }

            assertContains(error.message.orEmpty(), "from within a transaction")
            val waited = start.elapsedNow()
            assertTrue(waited < BUSY_TIMEOUT_MILLIS.milliseconds, "Took $waited")
        }

    private fun openInBackground(
        dir: TempDatabaseDir,
        schema: RecordingSchema,
    ): Deferred<Unit> =
        CoroutineScope(testThreads).async {
            openSqliteDriver(dir.path, schema, TEST_APPLICATION_ID).close()
        }

    private fun foreignFileIsRejected(vararg setup: String) =
        rejectedAndLeftUntouched("Not a database of this application", *setup)

    /** A file that plain SQLite set up with [setup], so still in its default journal mode. */
    private fun rejectedAndLeftUntouched(
        message: String,
        vararg setup: String,
    ) = withTempDir { dir ->
        val raw = openRawConnection(dir.path)
        setup.forEach(raw::execSQL)
        raw.close()
        val before = dir.bytes()

        val schema = RecordingSchema(version = 1)
        val error =
            assertFailsWith<IllegalStateException> {
                openSqliteDriver(dir.path, schema, TEST_APPLICATION_ID)
            }

        assertContains(error.message.orEmpty(), message)
        assertEquals(emptyList(), schema.calls)
        assertEquals(before, dir.bytes())
        assertFalse(dir.exists("-wal"))
        assertFalse(dir.exists("-shm"))
    }

    /**
     * Creates `item(id)`, optionally followed by an invalid statement; records every call and runs
     * [onWrite] in the create or migrate transaction.
     */
    private class RecordingSchema(
        override val version: Long,
        private val failCreate: Boolean = false,
        private val onWrite: () -> Unit = {},
    ) : SqlSchema<QueryResult.Value<Unit>> {
        val calls = mutableListOf<String>()
        val callbacks = mutableListOf<AfterVersion>()
        val foreignKeysSeen = mutableListOf<Long?>()

        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            calls += "create"
            foreignKeysSeen += driver.queryLong("PRAGMA foreign_keys")
            driver.execute(null, "CREATE TABLE item(id INTEGER PRIMARY KEY)", 0)
            if (failCreate) driver.execute(null, "CREATE TABLE broken(", 0)
            onWrite()
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
            onWrite()
            return QueryResult.Unit
        }
    }

    /**
     * Creates `parent` and a `child` row that references it, `ON DELETE CASCADE` so that a
     * `DROP TABLE parent` with foreign keys on would empty `child`. Migrates by [migration].
     */
    private class ParentChildSchema(
        override val version: Long = 1,
        private val migration: List<String> = emptyList(),
    ) : SqlSchema<QueryResult.Value<Unit>> {
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            driver.execute(null, "CREATE TABLE parent(id INTEGER PRIMARY KEY)", 0)
            driver.execute(
                null,
                "CREATE TABLE child(parent_id INTEGER REFERENCES parent(id) ON DELETE CASCADE)",
                0,
            )
            driver.execute(null, "INSERT INTO parent(id) VALUES (1)", 0)
            driver.execute(null, "INSERT INTO child(parent_id) VALUES (1)", 0)
            return QueryResult.Unit
        }

        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: AfterVersion,
        ): QueryResult.Value<Unit> {
            migration.forEach { sql -> driver.execute(null, sql, 0) }
            return QueryResult.Unit
        }
    }

    private companion object {
        val HOLD = 300.milliseconds
        const val CONCURRENT_OPEN_ROUNDS = 100
    }
}
