package io.github.sameerkrmishra.jotter.core.sqlite

import androidx.sqlite.SQLiteException
import androidx.sqlite.execSQL
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqliteBusyTest {
    @Test
    fun aDatabaseThatStaysLockedIsBusy() =
        withNotesDatabase { db ->
            db.sqlDriver.execute(null, "PRAGMA busy_timeout = 50", 0)
            val other = openRawConnection(db.sqlDriver.databasePath())
            try {
                other.execSQL("BEGIN IMMEDIATE")
                val error = assertFailsWith<SQLiteException> { db.transaction { db.insert("a") } }
                assertTrue(isSqliteBusy(error), "Not recognised as busy: ${error.message}")
            } finally {
                other.close()
            }
        }

    @Test
    fun otherSqliteErrorsAreNotBusy() =
        withNotesDatabase { db ->
            val error =
                assertFailsWith<SQLiteException> {
                    db.sqlDriver.execute(null, "INSERT INTO note(id, title) VALUES (1, NULL)", 0)
                }
            assertFalse(isSqliteBusy(error), "Recognised as busy: ${error.message}")
        }

    @Test
    fun extendedBusyCodesAreBusy() {
        // SQLITE_BUSY_RECOVERY, SQLITE_BUSY_SNAPSHOT and SQLITE_BUSY_TIMEOUT.
        listOf(261, 517, 773).forEach { code ->
            assertTrue(isSqliteBusy(SQLiteException("Error code: $code, message: locked")))
        }
    }

    @Test
    fun theTextOfAnErrorDoesNotMatter() {
        val text = "database is locked"
        assertFalse(isSqliteBusy(SQLiteException("Error code: 1, message: $text")))
        assertFalse(isSqliteBusy(SQLiteException(text)))
        assertFalse(isSqliteBusy(IllegalStateException("Error code: 5, message: $text")))
    }
}
