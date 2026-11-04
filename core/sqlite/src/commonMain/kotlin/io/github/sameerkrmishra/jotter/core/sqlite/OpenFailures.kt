package io.github.sameerkrmishra.jotter.core.sqlite

import androidx.sqlite.SQLiteException

/**
 * The file is a SQLite database of another application, or one with tables but no schema version.
 * [openSqliteDriver] left it untouched.
 */
public class ForeignDatabaseException(
    message: String,
) : IllegalStateException(message)

/**
 * The database was written by a newer version of the app: its schema version is above this one's.
 * [openSqliteDriver] left it untouched.
 *
 * @property version The database's schema version.
 * @property schemaVersion The newest this app knows.
 */
public class DatabaseTooNewException(
    public val version: Long,
    public val schemaVersion: Long,
) : IllegalStateException(
        "The database has schema version $version, newer than this app's $schemaVersion",
    )

/**
 * Migrating the database to this app's schema failed and was rolled back, so the file is as it
 * was. [cause] says why; a `SQLiteException` there may say that the disk is full. The message
 * never repeats the cause's, which may quote data: logs trust this class's own words only.
 */
public class DatabaseUpgradeException(
    cause: Throwable,
) : IllegalStateException("Upgrading the database failed", cause)

/** A copy of a database, such as one `VACUUM INTO` wrote, failed [checkDatabaseCopy]. */
public class DamagedCopyException(
    message: String,
) : IllegalStateException(message)

/** The extended result codes (such as `SQLITE_BUSY_SNAPSHOT`) keep the primary one here. */
private const val PRIMARY_RESULT_CODE_MASK = 0xFF

/** The result code as androidx SQLite formats it, the same on every platform. */
private val RESULT_CODE = Regex("""^Error code: (\d{1,9}),""")

/**
 * SQLite's primary result code in [error], such as 13 for `SQLITE_FULL`, if it is a
 * `SQLiteException` that has one. androidx `SQLiteException` has no property for it, but writes the
 * number into its message; this reads that number rather than the text SQLite gives for it.
 */
public fun sqliteResultCode(error: Throwable): Int? {
    val code = (error as? SQLiteException)?.message?.let(RESULT_CODE::find) ?: return null
    return code.groupValues[1].toInt() and PRIMARY_RESULT_CODE_MASK
}

/** Whether [error] came from SQLite, with a result code or without one. */
public fun isSqliteError(error: Throwable): Boolean = error is SQLiteException
