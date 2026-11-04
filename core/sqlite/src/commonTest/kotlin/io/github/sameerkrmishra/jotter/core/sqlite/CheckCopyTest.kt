package io.github.sameerkrmishra.jotter.core.sqlite

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** [checkDatabaseCopy]: a copy is sound, is this application's and has its schema version. */
class CheckCopyTest {
    /** A database in rollback mode, as `VACUUM INTO` writes one, made by [statements]. */
    private fun TempDatabaseDir.copy(
        applicationId: Int = TEST_APPLICATION_ID,
        version: Long = NotesSchema.version,
        vararg statements: String,
    ): String {
        BundledSQLiteDriver().open(path).use { connection ->
            connection.execSQL("PRAGMA application_id = $applicationId")
            connection.execSQL("PRAGMA user_version = $version")
            connection.execSQL("CREATE TABLE note (title TEXT)")
            connection.execSQL("CREATE INDEX note_title ON note (title)")
            connection.execSQL("INSERT INTO note VALUES ('milk'), ('bread'), ('tea')")
            statements.forEach(connection::execSQL)
        }
        return path
    }

    private fun check(path: String) =
        checkDatabaseCopy(path, TEST_APPLICATION_ID, NotesSchema.version)

    @Test
    fun aSoundCopyPassesAndIsLeftAsItWas() =
        withTempDir { dir ->
            val path = dir.copy()
            val before = dir.bytes()
            check(path)
            assertEquals(before, dir.bytes())
            assertFalse(dir.exists("-wal") || dir.exists("-shm") || dir.exists("-journal"))
        }

    @Test
    fun anotherApplicationsDatabaseIsNoCopy() =
        withTempDir { dir ->
            val failure =
                assertFailsWith<DamagedCopyException> { check(dir.copy(applicationId = 7)) }
            assertContains(failure.message.orEmpty(), "application id 7")
        }

    @Test
    fun anotherSchemaVersionIsNoCopy() {
        withTempDir { dir ->
            assertFailsWith<DamagedCopyException> { check(dir.copy(version = 99)) }
        }
    }

    @Test
    fun aCopyThatFailsItsIntegrityCheckIsDamaged() =
        withTempDir { dir ->
            // The index loses its rows while the table keeps them: integrity_check lists that.
            val path =
                dir.copy(
                    statements =
                        arrayOf(
                            "PRAGMA writable_schema = ON",
                            "UPDATE sqlite_master SET sql = 'CREATE TABLE note_title (x)', " +
                                "type = 'table', name = 'note_title', tbl_name = 'note_title' " +
                                "WHERE name = 'note_title'",
                            "PRAGMA writable_schema = OFF",
                        ),
                )
            BundledSQLiteDriver().open(path).use { connection ->
                connection.execSQL("DELETE FROM note_title")
                connection.execSQL("PRAGMA writable_schema = ON")
                connection.execSQL(
                    "UPDATE sqlite_master SET sql = 'CREATE INDEX note_title ON note (title)', " +
                        "type = 'index', tbl_name = 'note' WHERE name = 'note_title'",
                )
            }
            val failure = assertFailsWith<DamagedCopyException> { check(path) }
            assertContains(failure.message.orEmpty(), "integrity check")
        }
}
