package io.github.sameerkrmishra.jotter.core.sqlite

import androidx.sqlite.SQLiteException
import androidx.sqlite.execSQL
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Single-threaded transaction semantics, through SQLDelight's [app.cash.sqldelight.Transacter]. */
class TransactionTest {
    @Test
    fun committedTransactionKeepsItsWrites() =
        withNotesDatabase { db ->
            db.transaction {
                db.insert("a")
                db.insert("b")
            }
            assertEquals(listOf("a", "b"), db.titles().executeAsList())
            assertNull(db.sqlDriver.currentTransaction())
        }

    @Test
    fun rollbackDiscardsTheWrites() =
        withNotesDatabase { db ->
            db.transaction {
                db.insert("a")
                rollback()
            }
            assertEquals(0, db.count())
        }

    @Test
    fun exceptionRollsBackAndIsRethrown() =
        withNotesDatabase { db ->
            val error =
                assertFailsWith<IllegalStateException> {
                    db.transaction {
                        db.insert("a")
                        error("boom")
                    }
                }
            assertEquals("boom", error.message)
            assertEquals(0, db.count())
        }

    @Test
    fun nestedTransactionJoinsTheOuterOne() =
        withNotesDatabase { db ->
            db.transaction {
                db.insert("outer")
                db.transaction {
                    assertNotNull(db.sqlDriver.currentTransaction())
                    // Same SQLite transaction: the inner one sees the outer one's uncommitted row.
                    assertEquals(1, db.count())
                    db.insert("inner")
                }
            }
            assertEquals(listOf("outer", "inner"), db.titles().executeAsList())
        }

    @Test
    fun failedInnerTransactionRollsBackTheOuterOneToo() =
        withNotesDatabase { db ->
            db.transaction {
                db.insert("before")
                db.transaction {
                    db.insert("inner")
                    rollback()
                }
                db.insert("after")
            }
            assertEquals(0, db.count())
        }

    @Test
    fun rollingBackTheOuterTransactionDiscardsCommittedInnerOnes() =
        withNotesDatabase { db ->
            db.transaction {
                db.transaction { db.insert("inner") }
                rollback()
            }
            assertEquals(0, db.count())
        }

    @Test
    fun afterCommitHooksRunAfterTheOutermostCommitOnly() =
        withNotesDatabase { db ->
            val events = mutableListOf<String>()
            db.transaction {
                afterCommit { events += "outer committed, count ${db.count()}" }
                afterRollback { events += "outer rolled back" }
                db.transaction {
                    afterCommit { events += "inner committed" }
                    db.insert("a")
                }
                events += "inner done"
            }
            assertEquals(
                listOf("inner done", "outer committed, count 1", "inner committed"),
                events,
            )
        }

    @Test
    fun afterRollbackHooksRunOnRollbackOnly() =
        withNotesDatabase { db ->
            val events = mutableListOf<String>()
            db.transaction {
                afterCommit { events += "committed" }
                db.transaction { afterRollback { events += "inner rolled back" } }
                afterRollback { events += "outer rolled back" }
                rollback()
            }
            assertEquals(listOf("inner rolled back", "outer rolled back"), events)
        }

    @Test
    fun transactionTakesTheWriteLockAtBegin() =
        withNotesDatabase { db ->
            val other = openRawConnection(db.sqlDriver.databasePath())
            try {
                db.transaction {
                    // No statement has run yet. A deferred BEGIN would not hold any lock here.
                    val error =
                        assertFailsWith<SQLiteException> { other.execSQL("BEGIN IMMEDIATE") }
                    assertContains(error.message.orEmpty(), "database is locked")
                }
                other.execSQL("BEGIN IMMEDIATE")
                other.execSQL("COMMIT")
            } finally {
                other.close()
            }
        }

    @Test
    fun failedCommitIsRolledBackAndTheDriverStaysUsable() =
        withNotesDatabase { db ->
            val driver = db.sqlDriver
            val foreignKey = "REFERENCES note(id) DEFERRABLE INITIALLY DEFERRED"
            driver.execute(null, "CREATE TABLE tag(note_id INTEGER $foreignKey)", 0)
            val error =
                assertFailsWith<SQLiteException> {
                    // A deferred foreign key is only checked, and fails, at COMMIT.
                    db.transaction { driver.execute(null, "INSERT INTO tag VALUES (42)", 0) }
                }
            assertContains(error.message.orEmpty(), "FOREIGN KEY constraint failed")
            assertNull(driver.currentTransaction())
            assertEquals(0L, driver.queryLong("SELECT count(*) FROM tag"))
            db.transaction { db.insert("still works") }
            assertEquals(1, db.count())
        }

    @Test
    fun transactionEndedBehindTheDriversBackFailsToCommitButLeavesTheDriverUsable() =
        withNotesDatabase { db ->
            val error =
                assertFailsWith<SQLiteException> {
                    db.transaction {
                        db.insert("a")
                        db.sqlDriver.execute(null, "ROLLBACK", 0)
                    }
                }
            assertContains(error.message.orEmpty(), "no transaction is active")
            db.transaction { db.insert("b") }
            assertEquals(listOf("b"), db.titles().executeAsList())
        }

    @Test
    fun failedBeginReleasesTheDriver() =
        withNotesDatabase { db ->
            db.sqlDriver.execute(null, "PRAGMA busy_timeout = 50", 0)
            val other = openRawConnection(db.sqlDriver.databasePath())
            try {
                other.execSQL("BEGIN IMMEDIATE")
                val error = assertFailsWith<SQLiteException> { db.transaction { db.insert("a") } }
                assertContains(error.message.orEmpty(), "database is locked")
                other.execSQL("COMMIT")
            } finally {
                other.close()
            }
            assertNull(db.sqlDriver.currentTransaction())
            db.transaction { db.insert("b") }
            assertEquals(listOf("b"), db.titles().executeAsList())
        }
}
