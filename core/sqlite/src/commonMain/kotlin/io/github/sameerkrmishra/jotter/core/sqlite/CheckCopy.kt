package io.github.sameerkrmishra.jotter.core.sqlite

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY

/** A copy of a database, such as one `VACUUM INTO` wrote, failed [checkDatabaseCopy]. */
public class DamagedCopyException(
    message: String,
) : IllegalStateException(message)

/**
 * Checks that the database file at [path], such as a copy that `VACUUM INTO` just wrote, opens,
 * passes `PRAGMA integrity_check` and belongs to [applicationId] at [schemaVersion]. The file is
 * opened read-only and changes in no way; it must not be in WAL mode, which `VACUUM INTO` output
 * never is, or SQLite would make `-wal` and `-shm` files beside it.
 *
 * @throws DamagedCopyException if it is not a sound copy.
 * @throws androidx.sqlite.SQLiteException if it cannot be opened or read.
 */
public fun checkDatabaseCopy(
    path: String,
    applicationId: Int,
    schemaVersion: Long,
) {
    BundledSQLiteDriver().open(path, SQLITE_OPEN_READONLY).use { connection ->
        connection.prepare("PRAGMA integrity_check").use { statement ->
            val problems = buildList { while (statement.step()) add(statement.getText(0)) }
            if (problems != listOf("ok")) {
                throw DamagedCopyException(
                    "The copy failed its integrity check: ${problems.take(MAX_PROBLEMS_SHOWN)}",
                )
            }
        }
        connection.prepare(READ_IDENTITY).use { statement ->
            statement.step()
            val found = statement.getLong(0)
            val version = statement.getLong(1)
            if (found != applicationId.toLong() || version != schemaVersion) {
                throw DamagedCopyException(
                    "The copy has application id $found and schema version $version, not " +
                        "$applicationId and $schemaVersion",
                )
            }
        }
    }
}

private const val MAX_PROBLEMS_SHOWN = 5

private const val READ_IDENTITY =
    "SELECT (SELECT application_id FROM pragma_application_id), " +
        "(SELECT user_version FROM pragma_user_version)"
