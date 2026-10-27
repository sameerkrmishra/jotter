package io.github.sameerkrmishra.jotter.core.sqlite

import androidx.sqlite.SQLiteException
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.use
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Binding, reading and the statement cache, through the driver. */
class StatementsTest {
    @Test
    fun everyTypeRoundTripsIncludingNull() =
        withTempDir { dir ->
            openConnectionDriver(dir.path).use { driver ->
                driver.execute(
                    null,
                    "CREATE TABLE value(i INTEGER, r REAL, s TEXT, b BLOB, f INTEGER)",
                    0,
                )
                val insert = "INSERT INTO value VALUES (?, ?, ?, ?, ?)"
                driver.execute(1, insert, 5) {
                    bindLong(0, Long.MIN_VALUE)
                    bindDouble(1, 0.5)
                    bindString(2, "café")
                    bindBytes(3, byteArrayOf(0, -1, 7))
                    bindBoolean(4, true)
                }
                driver.execute(1, insert, 5) {
                    bindLong(0, null)
                    bindDouble(1, null)
                    bindString(2, null)
                    bindBytes(3, null)
                    bindBoolean(4, null)
                }
                driver.execute(2, "INSERT INTO value(f) VALUES (?)", 1) { bindBoolean(0, false) }

                val rows =
                    driver.selectAll(
                        "SELECT i, r, s, b, f FROM value ORDER BY rowid",
                    ) { it.readRow() }

                assertEquals(3, rows.size)
                assertEquals(
                    listOf(Long.MIN_VALUE, 0.5, "café", true),
                    rows[0].take(3) + rows[0][4],
                )
                assertContentEquals(byteArrayOf(0, -1, 7), rows[0][3] as ByteArray)
                assertEquals(listOf(null, null, null, null, null), rows[1])
                assertEquals(false, rows[2][4])
            }
        }

    @Test
    fun executeReturnsRowsChangedByThisStatementOnly() =
        withTempDir { dir ->
            openConnectionDriver(dir.path).use { driver ->
                fun run(sql: String) = driver.execute(null, sql, 0).value

                assertEquals(0L, run("CREATE TABLE note(title TEXT)"))
                assertEquals(0L, run("CREATE TABLE log(n INTEGER)"))
                val logInsert = "INSERT INTO log VALUES (1)"
                val trigger = "CREATE TRIGGER audit AFTER UPDATE ON note BEGIN $logInsert; END"
                assertEquals(0L, run(trigger))
                assertEquals(3L, run("INSERT INTO note VALUES ('a'), ('b'), ('c')"))
                // The trigger's insert into log is not counted.
                assertEquals(2L, run("UPDATE note SET title = 'x' WHERE title != 'a'"))
                assertEquals(0L, run("UPDATE note SET title = 'y' WHERE title = 'none'"))
                // changes() still says 2 here; a statement that changed nothing must return 0.
                assertEquals(0L, run("CREATE INDEX note_title ON note(title)"))
                assertEquals(3L, run("DELETE FROM note"))
            }
        }

    @Test
    fun cachedStatementStartsWithoutTheBindingsOfItsLastRun() =
        withTempDir { dir ->
            openConnectionDriver(dir.path).use { driver ->
                assertEquals(
                    "first",
                    driver.selectParameter(identifier = 7) { bindString(0, "first") },
                )
                // Without clearBindings() the cached statement would still return "first".
                assertNull(driver.selectParameter(identifier = 7, binders = null))
                assertEquals(
                    "third",
                    driver.selectParameter(identifier = 7) { bindString(0, "third") },
                )
            }
        }

    @Test
    fun statementCanRunAgainInsideItsOwnMapper() =
        withNotesDatabase { db ->
            db.insert("a")
            db.insert("b")
            // The inner run must not reuse (and reset) the statement the outer run is reading.
            val pairs =
                db.titles().executeAsList().flatMap { outer ->
                    db.titles().executeAsList().map { inner -> outer + inner }
                }
            assertEquals(listOf("aa", "ab", "ba", "bb"), pairs)
        }

    @Test
    fun failedCachedStatementIsNotReused() =
        withNotesDatabase { db ->
            val driver = db.sqlDriver
            val insert = "INSERT INTO note(title) VALUES (?)"
            assertFailsWith<SQLiteException> {
                driver.execute(
                    5,
                    insert,
                    1,
                ) { bindString(0, null) }
            }
            assertEquals(1L, driver.execute(5, insert, 1) { bindString(0, "ok") }.value)
            assertEquals(listOf("ok"), db.titles().executeAsList())
        }

    @Test
    fun failingMapperDoesNotBreakTheStatement() =
        withNotesDatabase { db ->
            db.insert("a")
            assertFailsWith<IllegalStateException> {
                // The identifier and SQL of NotesDatabase.titles(), so it uses the same cache slot.
                db.sqlDriver.executeQuery<Unit>(
                    identifier = 2,
                    sql = "SELECT title FROM note ORDER BY id",
                    mapper = { error("mapper failed") },
                    parameters = 0,
                )
            }
            assertEquals(listOf("a"), db.titles().executeAsList())
        }

    @Test
    fun queriesStayCorrectWhenTheCacheEvictsStatements() =
        withTempDir { dir ->
            openConnectionDriver(dir.path, statementCacheSize = 1).use { driver ->
                repeat(3) { round ->
                    for (identifier in 1..3) {
                        val result =
                            driver.selectParameter(
                                identifier,
                            ) { bindString(0, "$identifier.$round") }
                        assertEquals("$identifier.$round", result)
                    }
                }
            }
        }

    private fun SqlDriver.selectParameter(
        identifier: Int,
        binders: (app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit)?,
    ): String? =
        executeQuery(
            identifier = identifier,
            sql = "SELECT ?",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getString(0))
            },
            parameters = 1,
            binders = binders,
        ).value

    private fun <T> SqlDriver.selectAll(
        sql: String,
        readRow: (SqlCursor) -> T,
    ): List<T> =
        executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                val rows = mutableListOf<T>()
                while (cursor.next().value) rows += readRow(cursor)
                QueryResult.Value(rows)
            },
            parameters = 0,
        ).value

    private fun SqlCursor.readRow(): List<Any?> =
        listOf(getLong(0), getDouble(1), getString(2), getBytes(3), getBoolean(4))
}
