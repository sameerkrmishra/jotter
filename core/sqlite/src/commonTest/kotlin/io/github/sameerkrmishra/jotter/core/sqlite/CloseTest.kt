package io.github.sameerkrmishra.jotter.core.sqlite

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
