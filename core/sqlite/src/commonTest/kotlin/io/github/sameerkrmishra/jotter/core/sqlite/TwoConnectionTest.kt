package io.github.sameerkrmishra.jotter.core.sqlite

import androidx.sqlite.SQLiteException
import androidx.sqlite.execSQL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Two drivers on the same file, as the desktop app and the CLI would have. The JVM tests also run
 * this with two processes (TwoProcessTest).
 */
class TwoConnectionTest {
    @Test
    fun secondWriterWaitsForTheFirstInsteadOfFailing() =
        withTempDir { dir ->
            val first = NotesDatabase(openSqliteDriver(dir.path, NotesSchema))
            val second = NotesDatabase(openSqliteDriver(dir.path, NotesSchema))
            try {
                runBlocking {
                    val locked = CompletableDeferred<Unit>()
                    val firstWriter =
                        launch(testThreads) {
                            first.transaction {
                                first.insert("first")
                                locked.complete(Unit)
                                blockFor(HOLD)
                            }
                        }
                    locked.await()

                    // Control: without busy_timeout the same write fails at once.
                    val raw = openRawConnection(dir.path)
                    try {
                        val error =
                            assertFailsWith<SQLiteException> { raw.execSQL("BEGIN IMMEDIATE") }
                        assertContains(error.message.orEmpty(), "database is locked")
                    } finally {
                        raw.close()
                    }

                    val waited =
                        async(testThreads) {
                            val start = TimeSource.Monotonic.markNow()
                            second.transaction { second.insert("second") }
                            start.elapsedNow()
                        }.await()
                    firstWriter.join()
                    assertTrue(waited >= HOLD / 2, "second writer returned after $waited")
                }
                assertEquals(listOf("first", "second"), second.titles().executeAsList())
                assertEquals(listOf("first", "second"), first.titles().executeAsList())
            } finally {
                first.sqlDriver.close()
                second.sqlDriver.close()
            }
            assertTrue(!dir.exists("-wal") && !dir.exists("-shm"))
        }

    private companion object {
        val HOLD = 300.milliseconds
    }
}
