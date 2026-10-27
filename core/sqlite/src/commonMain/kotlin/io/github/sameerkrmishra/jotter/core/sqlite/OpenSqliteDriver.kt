package io.github.sameerkrmishra.jotter.core.sqlite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

/**
 * How long a connection waits for a lock held by another connection, usually in another process,
 * before failing with `SQLITE_BUSY` (docs/adr/0002-sqlite-via-sqldelight.md).
 */
internal const val BUSY_TIMEOUT_MILLIS = 5_000

/**
 * Opens the SQLite database file at [path] with the bundled SQLite build (the same one on Android,
 * the JVM and Linux), creating the file if it does not exist, and returns a SQLDelight driver
 * for it. Close the driver when done: closing checkpoints the WAL. Once the last connection to
 * the file (in any process) has closed, the file can be copied on its own.
 *
 * The connection is configured with:
 * - `journal_mode = WAL`, so readers and a writer in different processes do not block each other;
 * - `busy_timeout = 5000`, so a second process waits up to 5 s for a lock instead of failing;
 * - `foreign_keys = ON`, which SQLite leaves off by default.
 *
 * The schema version is kept in `PRAGMA user_version`. A new database (version 0) is created with
 * [SqlSchema.create]; an older one is migrated with [SqlSchema.migrate], running [callbacks] as
 * their versions are reached. Both happen in one transaction, so a failure leaves the file as it
 * was, and two processes opening a new file at once cannot both create it.
 *
 * The returned driver may be used from any thread. It serialises all access to its one connection,
 * and a transaction blocks other threads until it ends.
 *
 * @param path a file system path. WAL needs a real file, so `":memory:"` and `""` are rejected.
 *   The file must be on a local file system: WAL is unsafe on NFS or SMB.
 * @param schema the schema SQLDelight generated with `generateAsync = false`.
 * @throws IllegalStateException if the database's schema version is newer than [schema]'s (it was
 *   written by a newer version of the app), or if the file cannot be put into WAL mode.
 * @throws androidx.sqlite.SQLiteException if the file cannot be opened, or creating or migrating
 *   the schema fails.
 */
public fun openSqliteDriver(
    path: String,
    schema: SqlSchema<QueryResult.Value<Unit>>,
    vararg callbacks: AfterVersion,
): SqlDriver {
    val driver = openConnectionDriver(path)
    cleanUpOnFailure(cleanUp = driver::close) { createOrMigrate(driver, schema, callbacks) }
    return driver
}

/**
 * Opens [path] with the connection pragmas of [openSqliteDriver], but without touching the
 * schema. Closes the connection again if configuring it fails.
 */
internal fun openConnectionDriver(
    path: String,
    statementCacheSize: Int = STATEMENT_CACHE_SIZE,
): ConnectionSqlDriver {
    val connection = BundledSQLiteDriver().open(path)
    return cleanUpOnFailure(cleanUp = connection::close) {
        applyPragmas(connection)
        ConnectionSqlDriver(connection, statementCacheSize)
    }
}

private fun applyPragmas(connection: SQLiteConnection) {
    // First, so that switching to WAL below already waits for other processes' locks.
    connection.execSQL("PRAGMA busy_timeout = $BUSY_TIMEOUT_MILLIS")
    // journal_mode reports the mode it ended up in rather than failing, e.g. "memory" for an
    // in-memory database.
    val journalMode =
        connection.prepare("PRAGMA journal_mode = WAL").use { statement ->
            statement.step()
            statement.getText(0)
        }
    check(journalMode == "wal") {
        "Could not enable WAL for the database, journal_mode is $journalMode"
    }
    connection.execSQL("PRAGMA foreign_keys = ON")
}

private fun createOrMigrate(
    driver: SqlDriver,
    schema: SqlSchema<QueryResult.Value<Unit>>,
    callbacks: Array<out AfterVersion>,
) {
    SchemaTransacter(driver).transaction {
        // Read inside the transaction: BEGIN IMMEDIATE holds the write lock, so no other process
        // can create or migrate the schema between this read and the writes below.
        val version = readUserVersion(driver)
        check(version <= schema.version) {
            "The database has schema version $version, newer than this app's ${schema.version}"
        }
        if (version == 0L) {
            schema.create(driver)
        } else if (version < schema.version) {
            // migrate() takes a vararg. Copying a few callbacks costs nothing.
            @Suppress("SpreadOperator")
            schema.migrate(driver, version, schema.version, *callbacks)
        }
        if (version != schema.version) {
            // PRAGMA takes no bound parameters. The value is a Long, so formatting it is safe.
            driver.execute(null, "PRAGMA user_version = ${schema.version}", 0)
        }
    }
}

private fun readUserVersion(driver: SqlDriver): Long {
    val mapper = { cursor: SqlCursor ->
        cursor.next()
        QueryResult.Value(checkNotNull(cursor.getLong(0)))
    }
    return driver.executeQuery(identifier = null, sql = "PRAGMA user_version", mapper, 0).value
}

/** Gives [createOrMigrate] SQLDelight's transaction handling, including rollback on failure. */
private class SchemaTransacter(
    driver: SqlDriver,
) : TransacterImpl(driver)
