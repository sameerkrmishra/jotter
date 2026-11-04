package io.github.sameerkrmishra.jotter.core.sqlite

import androidx.sqlite.SQLiteException
import androidx.sqlite.execSQL
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenFailuresTest {
    @Test
    fun resultCodesAreReadFromTheMessage() {
        assertEquals(13, sqliteResultCode(SQLiteException("Error code: 13, message: disk full")))
        // SQLITE_IOERR_WRITE is an extended code of SQLITE_IOERR.
        assertEquals(10, sqliteResultCode(SQLiteException("Error code: 778, message: disk I/O")))
        assertNull(sqliteResultCode(SQLiteException("unable to open database file")))
        assertNull(sqliteResultCode(IllegalStateException("Error code: 13, message: x")))
    }

    @Test
    fun sqliteErrorsAreTold() {
        assertTrue(isSqliteError(SQLiteException("x")))
        assertFalse(isSqliteError(IllegalStateException("x")))
    }

    @Test
    fun anotherApplicationsDatabaseIsForeign() =
        withTempDir { dir ->
            openRawConnection(dir.path).apply { execSQL("PRAGMA application_id = 1") }.close()
            assertFailsWith<ForeignDatabaseException> {
                openSqliteDriver(dir.path, NotesSchema, TEST_APPLICATION_ID)
            }
            Unit
        }

    @Test
    fun aFileThatIsNoDatabaseIsLeftAsItWas() =
        withTempDir { dir ->
            val text = "Not SQLite at all, but more than a header's worth of text. ".repeat(4)
            FileSystem.SYSTEM.write(dir.path.toPath()) { writeUtf8(text) }
            val error =
                assertFailsWith<SQLiteException> {
                    openSqliteDriver(dir.path, NotesSchema, TEST_APPLICATION_ID)
                }
            assertEquals(SQLITE_NOTADB, sqliteResultCode(error))
            assertEquals(text, FileSystem.SYSTEM.read(dir.path.toPath()) { readUtf8() })
            assertFalse(dir.exists("-wal"))
        }

    private companion object {
        const val SQLITE_NOTADB = 26
    }
}
