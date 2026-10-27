package io.github.sameerkrmishra.jotter.core.sqlite

import androidx.sqlite.execSQL
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.use
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Assumes SQLITE_FCNTL_PERSIST_WAL is unset, the default of the androidx bundled build. */
class CloseTest {
    @Test
    fun closeLeavesOnlyTheDatabaseFile() =
        withTempDir { dir ->
            val db = NotesDatabase(openSqliteDriver(dir.path, NotesSchema))
            db.insert("a")
            assertTrue(dir.exists("-wal"), "WAL mode keeps a -wal file while open")
            assertTrue(dir.exists("-shm"), "WAL mode keeps a -shm file while open")

            db.sqlDriver.close()

            assertTrue(dir.exists())
            assertFalse(dir.exists("-wal"))
            assertFalse(dir.exists("-shm"))
            // The row was checkpointed into the main file.
            openSqliteDriver(dir.path, NotesSchema).use { driver ->
                assertEquals(listOf("a"), NotesDatabase(driver).titles().executeAsList())
            }
        }

    @Test
    fun closeReportsAWalThatAnotherReaderKeepsAlive() =
        withTempDir { dir ->
            val driver = openConnectionDriver(dir.path)
            NotesSchema.create(driver)
            driver.execute(null, "INSERT INTO note(title) VALUES ('a')", 0)
            // So that the checkpoint gives up quickly instead of after 5 s.
            driver.execute(null, "PRAGMA busy_timeout = 50", 0)
            val reader = openRawConnection(dir.path)
            try {
                reader.execSQL("BEGIN")
                reader.prepare("SELECT count(*) FROM note").use { it.step() }

                driver.close()

                assertEquals(false, driver.walCheckpointedOnClose)
                assertTrue(dir.exists("-wal"))
                reader.execSQL("COMMIT")
            } finally {
                reader.close()
            }
            // The last connection to close checkpoints and removes the WAL.
            assertFalse(dir.exists("-wal"))
        }

    @Test
    fun closeReportsACompleteCheckpoint() =
        withTempDir { dir ->
            val driver = openConnectionDriver(dir.path)
            assertEquals(null, driver.walCheckpointedOnClose)
            driver.close()
            assertEquals(true, driver.walCheckpointedOnClose)
        }

    @Test
    fun closeInsideAMapperIsRejected() =
        withNotesDatabase { db ->
            db.insert("a")
            val error =
                assertFailsWith<IllegalStateException> {
                    db.titles().execute {
                        db.sqlDriver.close()
                        QueryResult.Unit
                    }
                }
            assertContains(error.message.orEmpty(), "while a statement runs")
            assertEquals(listOf("a"), db.titles().executeAsList())
        }

    @Test
    fun closingTwiceIsHarmless() =
        withTempDir { dir ->
            val driver = openSqliteDriver(dir.path, NotesSchema)
            driver.close()
            driver.close()
        }

    @Test
    fun closedDriverRejectsWork() =
        withTempDir { dir ->
            val db = NotesDatabase(openSqliteDriver(dir.path, NotesSchema))
            db.sqlDriver.close()

            assertClosed { db.insert("a") }
            assertClosed { db.count() }
            assertClosed { db.transaction {} }
        }

    @Test
    fun closeInsideATransactionIsRejected() =
        withNotesDatabase { db ->
            db.transaction {
                db.insert("a")
                val error = assertFailsWith<IllegalStateException> { db.sqlDriver.close() }
                assertContains(error.message.orEmpty(), "inside a transaction")
            }
            assertEquals(1, db.count())
        }

    @Test
    fun closeWaitsForATransactionOnAnotherThread() =
        withTempDir { dir ->
            val db = NotesDatabase(openSqliteDriver(dir.path, NotesSchema))
            runBlocking {
                val opened = CompletableDeferred<Unit>()
                val writer =
                    launch(testThreads) {
                        db.transaction {
                            opened.complete(Unit)
                            blockFor(200.milliseconds)
                            db.insert("committed before close")
                        }
                    }
                opened.await()
                db.sqlDriver.close()
                writer.join()
            }
            openSqliteDriver(dir.path, NotesSchema).use { driver ->
                assertEquals(
                    listOf("committed before close"),
                    NotesDatabase(driver).titles().executeAsList(),
                )
            }
        }

    private fun assertClosed(block: () -> Unit) {
        val error = assertFailsWith<IllegalStateException> { block() }
        assertEquals("The database is closed", error.message)
    }
}
