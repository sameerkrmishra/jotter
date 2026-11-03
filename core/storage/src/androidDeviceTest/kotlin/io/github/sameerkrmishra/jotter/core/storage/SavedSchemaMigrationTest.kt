package io.github.sameerkrmishra.jotter.core.storage

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.use
import io.github.sameerkrmishra.jotter.core.sqlite.openSqliteDriver
import io.github.sameerkrmishra.jotter.core.storage.db.JotterDatabase
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.source
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `verifyMigrations` checks the migrations on the host, with SQLDelight's own SQLite. This runs
 * them on the device, through the app's open path and its bundled SQLite: every saved schema
 * (`src/commonMain/sqldelight/databases/N.db`, packed into the test APK) must migrate to the
 * schema that a new database gets, and keep a sound full-text index.
 */
class SavedSchemaMigrationTest {
    @Test
    fun everySavedSchemaMigratesToTheCurrentOne() {
        val current = JotterDatabase.Schema.version
        val expected = withTempDir { dir -> open(dir.path).use { it.schemaRows() } }
        for (version in 1 until current) {
            withTempDir { dir ->
                copySavedSchema(version, dir.path)
                open(dir.path).use { driver ->
                    assertEquals(current, driver.queryLong("PRAGMA user_version"), "from $version")
                    assertEquals(expected, driver.schemaRows(), "from $version")
                    driver.execute(null, INTEGRITY_CHECK, 0)
                }
            }
        }
    }

    @Test
    fun theSavedSchemasCoverEveryVersion() {
        val current = JotterDatabase.Schema.version.toInt()
        val saved = (1..current).map { javaClass.classLoader?.getResource("$it.db") != null }
        assertEquals(List(current) { true }, saved)
    }

    private fun open(path: String): SqlDriver =
        openSqliteDriver(path, JotterDatabase.Schema, JOTTER_APPLICATION_ID)

    /** The saved schema of [version], marked as the app's database at that version. */
    private fun copySavedSchema(
        version: Long,
        path: String,
    ) {
        val resource = requireNotNull(javaClass.classLoader?.getResourceAsStream("$version.db"))
        resource.source().buffer().use { source ->
            FileSystem.SYSTEM.write(path.toPath()) { writeAll(source) }
        }
        BundledSQLiteDriver().open(path).use { connection ->
            connection.execSQL("PRAGMA application_id = $JOTTER_APPLICATION_ID")
            connection.execSQL("PRAGMA user_version = $version")
        }
    }

    /**
     * Every table, index, trigger and view, with its SQL, shadow tables of FTS5 included. The SQL
     * is compared without comments, quotes and layout, which a migration's `CREATE` and a rename
     * change.
     */
    private fun SqlDriver.schemaRows(): List<String> =
        executeQuery(
            identifier = null,
            sql = "SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name",
            mapper = { cursor ->
                val rows = mutableListOf<String>()
                while (cursor.next().value) {
                    rows += (0..3).joinToString(" | ") { normalise(cursor.getString(it).orEmpty()) }
                }
                QueryResult.Value(rows)
            },
            parameters = 0,
        ).value

    private fun normalise(sql: String): String =
        sql
            .replace(Regex("--[^\n]*"), "")
            .replace("\"", "")
            .replace(Regex("\\s+"), " ")
            .replace(Regex(" ?([(),]) ?"), "$1")
            .trim()

    private companion object {
        const val INTEGRITY_CHECK = "INSERT INTO note_fts(note_fts) VALUES ('integrity-check')"
    }
}
