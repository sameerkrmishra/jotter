package io.github.sameerkrmishra.jotter.core.sqlite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

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
 * Before any of this changes the file, it is checked to be this application's: a database with
 * another `PRAGMA application_id`, or with tables but neither [applicationId] nor a schema
 * version, is rejected and left untouched, so a mistyped path does not end up with tables in
 * someone else's database.
 *
 * The schema version is kept in `PRAGMA user_version`. A new database (version 0) gets
 * [applicationId] and is created with [SqlSchema.create]; an older one is migrated with
 * [SqlSchema.migrate], running [callbacks] as their versions are reached. Each happens in one
 * transaction, so a failure leaves the file as it was, and two processes opening a new file at
 * once cannot both create it.
 *
 * A migration runs with `foreign_keys` off, so it may rebuild a table that others reference, and
 * [SqlSchema.migrate] does not have to keep foreign keys valid step by step. They are checked
 * before the commit: a row left with a dangling reference fails the migration and rolls it back.
 *
 * The returned driver may be used from any thread. It serialises all access to its one connection,
 * and a transaction blocks other threads until it ends.
 *
 * @param path a file system path. WAL needs a real file, so `":memory:"` and `""` are rejected.
 *   The file must be on a local file system: WAL is unsafe on NFS or SMB.
 * @param schema the schema SQLDelight generated with `generateAsync = false`.
 * @param applicationId the application's `PRAGMA application_id`. Not 0, which is what a database
 *   has when nothing set one.
 * @throws IllegalArgumentException if [applicationId] is 0.
 * @throws IllegalStateException if the file is a database of another application, if the
 *   database's schema version is newer than [schema]'s (it was written by a newer version of the
 *   app), if the file cannot be put into WAL mode, or if a migration left a dangling foreign key.
 * @throws androidx.sqlite.SQLiteException if the file cannot be opened, or creating or migrating
 *   the schema fails.
 */
public fun openSqliteDriver(
    path: String,
    schema: SqlSchema<QueryResult.Value<Unit>>,
    applicationId: Int,
    vararg callbacks: AfterVersion,
): SqlDriver {
    require(applicationId != 0) { "0 is the application id of a database that has none" }
    val driver =
        openConnectionDriver(path) { connection ->
            checkOpenable(connection, applicationId, schema.version)
        }
    cleanUpOnFailure(cleanUp = driver::close) {
        createOrMigrate(driver, schema, applicationId, callbacks)
    }
    return driver
}

/**
 * Opens [path] with the connection pragmas of [openSqliteDriver], but without touching the
 * schema. Closes the connection again if configuring it fails. [inspect] sees the connection
 * before the first pragma that writes to the file, so it can refuse the file by throwing.
 */
internal fun openConnectionDriver(
    path: String,
    statementCacheSize: Int = STATEMENT_CACHE_SIZE,
    inspect: (SQLiteConnection) -> Unit = {},
): ConnectionSqlDriver {
    val connection = BundledSQLiteDriver().open(path)
    return cleanUpOnFailure(cleanUp = connection::close) {
        // First, so that everything below already waits for other processes' locks.
        connection.execSQL("PRAGMA busy_timeout = $BUSY_TIMEOUT_MILLIS")
        inspect(connection)
        applyPragmas(connection)
        ConnectionSqlDriver(connection, statementCacheSize)
    }
}

/**
 * Throws unless the database is new or was created with [applicationId], at no newer version than
 * [schemaVersion]. It only reads: switching to WAL rewrites the file header, so this has to run
 * first to leave a rejected file untouched.
 *
 * All of it is read by one statement, so from one snapshot. Read separately, a new file that
 * another process is creating could show up with its schema version but not yet its id.
 */
private fun checkOpenable(
    connection: SQLiteConnection,
    applicationId: Int,
    schemaVersion: Long,
) {
    connection.prepare(READ_OWNERSHIP).use { statement ->
        statement.step()
        val found = statement.getLong(0)
        val version = statement.getLong(1)
        val objects = statement.getLong(2)
        val isNew = version == 0L && objects == 0L
        val isOurs = found == applicationId.toLong() && (isNew || version != 0L)
        check(isOurs || (isNew && found == 0L)) {
            "Not a database of this application: its application id is $found, its schema " +
                "version $version, and it has $objects schema objects"
        }
        checkNotNewer(version, schemaVersion)
    }
}

private const val READ_OWNERSHIP =
    "SELECT (SELECT application_id FROM pragma_application_id), " +
        "(SELECT user_version FROM pragma_user_version), " +
        "(SELECT count(*) FROM sqlite_master)"

private fun applyPragmas(connection: SQLiteConnection) {
    // journal_mode reports the mode it ended up in rather than failing, e.g. "memory" for an
    // in-memory database.
    val journalMode = enableWal(connection)
    check(journalMode == "wal") {
        "Could not enable WAL for the database, journal_mode is $journalMode"
    }
    connection.execSQL("PRAGMA foreign_keys = ON")
}

/**
 * Switches to WAL and returns the journal mode that took effect. Another connection's open can
 * make this fail with `SQLITE_BUSY` at once: the switch needs an exclusive lock, and SQLite does
 * not run its busy handler for that when two connections would wait for each other. So this
 * retries for as long as `busy_timeout`, then throws the last error. There is no pause between
 * tries: such a failure only happens while the other open is switching, and a lock held for long
 * makes each try wait in the busy handler.
 */
private fun enableWal(connection: SQLiteConnection): String {
    val deadline = TimeSource.Monotonic.markNow() + BUSY_TIMEOUT_MILLIS.milliseconds
    while (true) {
        try {
            return connection.prepare("PRAGMA journal_mode = WAL").use { statement ->
                statement.step()
                statement.getText(0)
            }
        } catch (e: SQLiteException) {
            if (!isSqliteBusy(e) || deadline.hasPassedNow()) throw e
        }
    }
}
