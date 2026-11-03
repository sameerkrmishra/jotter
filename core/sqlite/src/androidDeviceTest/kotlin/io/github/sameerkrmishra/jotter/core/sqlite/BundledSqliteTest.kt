package io.github.sameerkrmishra.jotter.core.sqlite

import android.database.sqlite.SQLiteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On a device the driver must use the SQLite that sqlite-bundled ships, not Android's own, whose
 * version depends on the API level (ADR-0002). Fts5Test checks FTS5 itself.
 */
class BundledSqliteTest {
    @Test
    fun theDriverRunsTheBundledSqliteWithFts5() =
        withNotesDatabase { db ->
            assertEquals(BUNDLED_VERSION, db.sqlDriver.queryText("SELECT sqlite_version()"))
            val fts5 = db.sqlDriver.queryText("SELECT sqlite_compileoption_used('ENABLE_FTS5')")
            assertEquals("1", fts5)
        }

    @Test
    fun androidsOwnSqliteIsAnotherBuild() {
        // Shows that the check above can fail: the platform's library is not the bundled one.
        val platform =
            SQLiteDatabase.create(null).use { database ->
                database.compileStatement("SELECT sqlite_version()").simpleQueryForString()
            }
        assertTrue(platform != BUNDLED_VERSION, "Android's own SQLite is $platform")
    }

    private companion object {
        /** sqlite-bundled 2.7.1 (gradle/libs.versions.toml). */
        const val BUNDLED_VERSION = "3.50.1"
    }
}
