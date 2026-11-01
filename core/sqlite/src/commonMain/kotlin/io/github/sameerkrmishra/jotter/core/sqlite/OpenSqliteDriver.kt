package io.github.sameerkrmishra.jotter.core.sqlite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
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
        openConnectionDriver(path) { connection -> checkOwnership(connection, applicationId) }
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
 * Throws unless the database is new or was created with [applicationId]. It only reads: switching
 * to WAL rewrites the file header, so this has to run first to leave a rejected file untouched.
 *
 * All of it is read by one statement, so from one snapshot. Read separately, a new file that
 * another process is creating could show up with its schema version but not yet its id.
 */
private fun checkOwnership(
    connection: SQLiteConnection,
    applicationId: Int,
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

/**
 * Creates a new database, then migrates an older one. They are two steps because a migration has
 * to change a pragma outside its transaction, and only a transaction can tell for sure that a
 * database is new. After the first step the version is never 0 again and never goes down, so what
 * is read between them is safe to act on.
 */
internal fun createOrMigrate(
    driver: SqlDriver,
    schema: SqlSchema<QueryResult.Value<Unit>>,
    applicationId: Int,
    callbacks: Array<out AfterVersion>,
) {
    if (readUserVersion(driver) == 0L) create(driver, schema, applicationId)
    if (readCheckedVersion(driver, schema) < schema.version) migrate(driver, schema, callbacks)
}

private fun create(
    driver: SqlDriver,
    schema: SqlSchema<QueryResult.Value<Unit>>,
    applicationId: Int,
) {
    SchemaTransacter(driver).transaction {
        // Read again under the write lock that BEGIN IMMEDIATE holds: another process may have
        // created the schema in the meantime.
        if (readUserVersion(driver) == 0L) {
            // PRAGMA takes no bound parameters. The value is an Int, so formatting it is safe.
            driver.execute(null, "PRAGMA application_id = $applicationId", 0)
            schema.create(driver)
            writeUserVersion(driver, schema.version)
        }
    }
}

/**
 * Migrates the database by SQLite's procedure for schema changes (section 7 of
 * sqlite.org/lang_altertable.html). `foreign_keys` cannot change inside a transaction, so it is
 * off for the whole transaction, on again afterwards whatever happened, and
 * `PRAGMA foreign_key_check` stands in for it before the commit. With it on, a migration that
 * rebuilds a referenced table would cascade its `DROP TABLE` into the child tables, or fail.
 */
private fun migrate(
    driver: SqlDriver,
    schema: SqlSchema<QueryResult.Value<Unit>>,
    callbacks: Array<out AfterVersion>,
) {
    driver.execute(null, "PRAGMA foreign_keys = OFF", 0)
    try {
        SchemaTransacter(driver).transaction {
            // Read again under the write lock: another process may have migrated in the meantime.
            val version = readCheckedVersion(driver, schema)
            if (version < schema.version) {
                // migrate() takes a vararg. Copying a few callbacks costs nothing.
                @Suppress("SpreadOperator")
                schema.migrate(driver, version, schema.version, *callbacks)
                checkForeignKeys(driver)
                writeUserVersion(driver, schema.version)
            }
        }
    } finally {
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
    }
}

/** Throws, which rolls the migration back, if a row references a parent that does not exist. */
private fun checkForeignKeys(driver: SqlDriver) {
    val mapper = { cursor: SqlCursor ->
        val hasRow = cursor.next().value
        QueryResult.Value(if (hasRow) "${cursor.getString(0)} -> ${cursor.getString(2)}" else null)
    }
    val violation =
        driver.executeQuery(identifier = null, "PRAGMA foreign_key_check", mapper, 0).value
    check(violation == null) { "The migration left a dangling foreign key: $violation" }
}

private fun readCheckedVersion(
    driver: SqlDriver,
    schema: SqlSchema<QueryResult.Value<Unit>>,
): Long {
    val version = readUserVersion(driver)
    check(version <= schema.version) {
        "The database has schema version $version, newer than this app's ${schema.version}"
    }
    return version
}

private fun readUserVersion(driver: SqlDriver): Long {
    val mapper = { cursor: SqlCursor ->
        cursor.next()
        QueryResult.Value(checkNotNull(cursor.getLong(0)))
    }
    return driver.executeQuery(identifier = null, sql = "PRAGMA user_version", mapper, 0).value
}

private fun writeUserVersion(
    driver: SqlDriver,
    version: Long,
) {
    // PRAGMA takes no bound parameters. The value is a Long, so formatting it is safe.
    driver.execute(null, "PRAGMA user_version = $version", 0)
}

/** Gives [createOrMigrate] SQLDelight's transaction handling, including rollback on failure. */
private class SchemaTransacter(
    driver: SqlDriver,
) : TransacterImpl(driver)
