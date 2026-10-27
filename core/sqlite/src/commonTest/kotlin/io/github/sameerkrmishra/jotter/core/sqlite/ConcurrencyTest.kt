package io.github.sameerkrmishra.jotter.core.sqlite

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** One driver used from several threads at once. */
class ConcurrencyTest {
    @Test
    fun concurrentTransactionsDoNotLoseUpdates() =
        withNotesDatabase { db ->
            val driver = db.sqlDriver
            driver.execute(null, "CREATE TABLE counter(value INTEGER NOT NULL)", 0)
            driver.execute(null, "INSERT INTO counter VALUES (0)", 0)
            runBlocking {
                repeat(WORKERS) {
                    launch(testThreads) {
                        repeat(INCREMENTS) {
                            // Read, then write: two threads interleaving here would lose an update.
                            db.transaction { driver.increment() }
                        }
                    }
                }
            }
            assertEquals(
                (WORKERS * INCREMENTS).toLong(),
                driver.queryLong("SELECT value FROM counter"),
            )
        }

    @Test
    fun anotherThreadNeverSeesUncommittedRows() =
        withNotesDatabase { db ->
            runBlocking {
                val inserted = CompletableDeferred<Unit>()
                val writer =
                    launch(testThreads) {
                        db.transaction {
                            db.insert("uncommitted")
                            inserted.complete(Unit)
                            blockFor(HOLD)
                            rollback()
                        }
                    }
                inserted.await()
                // On a shared connection without the lock this would count the uncommitted row.
                val seen = async(testThreads) { db.count() }
                assertEquals(0L, seen.await())
                writer.join()
            }
        }

    @Test
    fun writeFromAnotherThreadIsNotPartOfAnOpenTransaction() =
        withNotesDatabase { db ->
            runBlocking {
                val inserted = CompletableDeferred<Unit>()
                val writer =
                    launch(testThreads) {
                        db.transaction {
                            db.insert("rolled back")
                            inserted.complete(Unit)
                            blockFor(HOLD)
                            rollback()
                        }
                    }
                inserted.await()
                // Without the lock this insert would run inside the transaction above and be
                // rolled back with it.
                launch(testThreads) { db.insert("kept") }.join()
                writer.join()
            }
            assertEquals(listOf("kept"), db.titles().executeAsList())
        }

    @Test
    fun currentTransactionIsNullOnOtherThreads() =
        withNotesDatabase { db ->
            runBlocking {
                val opened = CompletableDeferred<Unit>()
                val writer =
                    launch(testThreads) {
                        db.transaction {
                            opened.complete(Unit)
                            blockFor(HOLD)
                        }
                    }
                opened.await()
                assertNull(async(testThreads) { db.sqlDriver.currentTransaction() }.await())
                writer.join()
            }
        }

    @Test
    fun rejectedNoEnclosingTransactionDoesNotKeepTheDriverLocked() =
        withNotesDatabase { db ->
            db.transaction {
                db.insert("a")
                // SQLDelight opens the nested transaction, then throws without ending it.
                assertFailsWith<IllegalStateException> { db.transaction(noEnclosing = true) {} }
            }
            // Not a child of runBlocking: if the lock had leaked, this thread would block for
            // good, and the test must fail on the timeout instead of hanging.
            val count = CoroutineScope(testThreads).async { db.count() }
            assertEquals(1L, runBlocking { withTimeout(5.seconds) { count.await() } })
        }

    private fun SqlDriver.increment() {
        val value = checkNotNull(queryLong("SELECT value FROM counter"))
        execute(1, "UPDATE counter SET value = ?", 1) { bindLong(0, value + 1) }
    }

    private companion object {
        const val WORKERS = 8
        const val INCREMENTS = 25

        // The tests above that hold a transaction open for HOLD assume the other thread runs
        // within that time. A scheduling stall can make them pass without testing anything
        // (the other thread runs after the transaction), but never fail falsely.
        val HOLD = 200.milliseconds
    }
}
