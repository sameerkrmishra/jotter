package io.github.sameerkrmishra.jotter.core.sqlite

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

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

/** Another process with a newer schema may have migrated the file since it was first checked. */
private fun readCheckedVersion(
    driver: SqlDriver,
    schema: SqlSchema<QueryResult.Value<Unit>>,
): Long {
    val version = readUserVersion(driver)
    checkNotNewer(version, schema.version)
    return version
}

internal fun checkNotNewer(
    version: Long,
    schemaVersion: Long,
) {
    check(version <= schemaVersion) {
        "The database has schema version $version, newer than this app's $schemaVersion"
    }
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
