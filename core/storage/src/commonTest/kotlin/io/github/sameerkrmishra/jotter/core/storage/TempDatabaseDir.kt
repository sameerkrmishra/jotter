package io.github.sameerkrmishra.jotter.core.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.use
import io.github.sameerkrmishra.jotter.core.sqlite.openSqliteDriver
import io.github.sameerkrmishra.jotter.core.storage.db.JotterDatabase
import okio.FileSystem
import okio.Path
import kotlin.random.Random

/** A fresh temporary directory for one test's database files, deleted again by [close]. */
internal class TempDatabaseDir : AutoCloseable {
    private val dir: Path =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "jotter-storage-test-${Random.nextLong().toULong()}"

    init {
        FileSystem.SYSTEM.createDirectories(dir)
    }

    /** The path of the database file, which does not exist until a test opens it. */
    val path: String = (dir / "test.db").toString()

    override fun close() = FileSystem.SYSTEM.deleteRecursively(dir)
}

/** Runs [block] with a fresh [TempDatabaseDir] and deletes it afterwards. */
internal inline fun <T> withTempDir(block: (TempDatabaseDir) -> T): T = TempDatabaseDir().use(block)

/** Runs [block] with a new, empty database and its driver, closing it afterwards. */
internal inline fun <T> withDatabase(block: (JotterDatabase, SqlDriver) -> T): T =
    withTempDir { dir ->
        openSqliteDriver(dir.path, JotterDatabase.Schema).use { driver ->
            block(JotterDatabase(driver), driver)
        }
    }

/** Returns the first column of the first row of [sql] as a number. */
internal fun SqlDriver.queryLong(sql: String): Long? =
    executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0))
        },
        parameters = 0,
    ).value
