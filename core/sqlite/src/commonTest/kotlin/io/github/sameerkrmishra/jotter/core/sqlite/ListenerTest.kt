package io.github.sameerkrmishra.jotter.core.sqlite

import app.cash.sqldelight.Query
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ListenerTest {
    @Test
    fun notifyReachesListenersOfTheGivenKeysOnce() =
        withNotesDatabase { db ->
            val driver = db.sqlDriver
            val noteAndTag = CountingListener()
            val tagOnly = CountingListener()
            driver.addListener("note", "tag", listener = noteAndTag)
            driver.addListener("tag", listener = tagOnly)

            driver.notifyListeners("note", "tag")
            assertEquals(1, noteAndTag.count)
            assertEquals(1, tagOnly.count)

            driver.notifyListeners("note", "other")
            assertEquals(2, noteAndTag.count)
            assertEquals(1, tagOnly.count)
        }

    @Test
    fun removedListenerIsNotNotified() =
        withNotesDatabase { db ->
            val driver = db.sqlDriver
            val listener = CountingListener()
            driver.addListener("note", "tag", listener = listener)
            driver.removeListener("note", "tag", "never-added", listener = listener)

            driver.notifyListeners("note", "tag")
            assertEquals(0, listener.count)
        }

    @Test
    fun writeOutsideATransactionNotifiesAtOnce() =
        withNotesDatabase { db ->
            val listener = CountingListener()
            db.titles().addListener(listener)
            db.insert("a")
            assertEquals(1, listener.count)
        }

    @Test
    fun writesInATransactionNotifyOnceAfterCommit() =
        withNotesDatabase { db ->
            val listener = CountingListener()
            db.titles().addListener(listener)
            db.transaction {
                db.insert("a")
                db.transaction { db.insert("b") }
                assertEquals(0, listener.count)
            }
            assertEquals(1, listener.count)
        }

    @Test
    fun rolledBackTransactionDoesNotNotify() =
        withNotesDatabase { db ->
            val listener = CountingListener()
            db.titles().addListener(listener)
            db.transaction {
                db.insert("a")
                rollback()
            }
            assertEquals(0, listener.count)
        }

    @Test
    fun listenerCanQueryFromAnotherThreadBecauseTheDriverIsReleasedFirst() =
        withNotesDatabase { db ->
            var seenByOtherThread = -1L
            db.titles().addListener {
                // If the committing thread still held the driver, this would wait forever.
                seenByOtherThread =
                    runBlocking(testThreads) { withTimeout(5.seconds) { db.count() } }
            }
            db.transaction { db.insert("a") }
            assertEquals(1L, seenByOtherThread)
        }

    @Test
    fun asFlowEmitsTheCommittedResultAfterATransaction() =
        withNotesDatabase { db ->
            runBlocking {
                val results = Channel<List<String>>(Channel.UNLIMITED)
                val collector =
                    launch(testThreads) {
                        val titles = db.titles().asFlow().mapToList(testThreads)
                        titles.collect { results.send(it) }
                    }
                // The first emission comes after asFlow() has registered its listener.
                assertEquals(emptyList(), withTimeout(5.seconds) { results.receive() })
                db.transaction {
                    db.insert("a")
                    db.insert("b")
                }
                assertEquals(listOf("a", "b"), withTimeout(5.seconds) { results.receive() })
                collector.cancel()
            }
        }

    @Test
    fun listenersCanBeAddedRemovedAndNotifiedFromManyThreads() =
        withNotesDatabase { db ->
            val driver = db.sqlDriver
            val listeners = List(THREADS) { CountingListener() }
            runBlocking {
                listeners.forEachIndexed { index, listener ->
                    launch(testThreads) {
                        repeat(ROUNDS) {
                            driver.addListener("note", "key$index", listener = listener)
                            driver.notifyListeners("key$index")
                            driver.removeListener("note", "key$index", listener = listener)
                        }
                    }
                }
            }
            // Each listener saw at least its own notifications, and all are gone now.
            listeners.forEach { assertTrue(it.count >= ROUNDS) }
            val before = listeners.map { it.count }
            driver.notifyListeners("note", *Array(THREADS) { "key$it" })
            assertEquals(before, listeners.map { it.count })
        }

    /** Counts notifications. Thread-safe, because notifications can come from any thread. */
    private class CountingListener : Query.Listener {
        private val lock = reentrantLock()
        private var calls = 0

        val count: Int get() = lock.withLock { calls }

        override fun queryResultsChanged() {
            lock.withLock { calls++ }
        }
    }

    private companion object {
        const val THREADS = 8
        const val ROUNDS = 200
    }
}
