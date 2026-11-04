package io.github.sameerkrmishra.jotter.core.storage

import io.github.sameerkrmishra.jotter.core.sqlite.DatabaseTooNewException
import io.github.sameerkrmishra.jotter.core.sqlite.DatabaseUpgradeException
import io.github.sameerkrmishra.jotter.core.sqlite.ForeignDatabaseException
import io.github.sameerkrmishra.jotter.core.sqlite.isSqliteError
import io.github.sameerkrmishra.jotter.core.sqlite.sqliteResultCode

/**
 * What went wrong with the database, for the words an app shows. [storageProblemOf] tells it from
 * the exception that [NoteStore.open] or a [NoteService] call threw. In none of these cases did
 * Jotter change or replace the file: a failed write is rolled back, and a file that cannot be
 * opened is left as it is.
 */
public enum class StorageProblem {
    /** Another process kept the database locked. Trying again may work. */
    LOCKED,

    /** The disk is full: nothing could be written. */
    DISK_FULL,

    /** The file is damaged, or is not a database at all. */
    DAMAGED,

    /** The file can be read but not written. */
    READ_ONLY,

    /** The file or its folder cannot be opened: missing, a directory, or not allowed. */
    NO_ACCESS,

    /** The disk failed to read or write, such as a removed drive. */
    DISK_ERROR,

    /** A newer version of Jotter wrote the database. */
    NEWER_VERSION,

    /** The file is a database of another application. */
    NOT_JOTTER,

    /** Upgrading the database to this version failed and was rolled back. */
    UPGRADE_FAILED,

    /** Anything else, which may be a bug. */
    OTHER,
}

// SQLite's primary result codes (sqlite.org/rescode.html).
private const val SQLITE_PERM = 3
private const val SQLITE_BUSY = 5
private const val SQLITE_READONLY = 8
private const val SQLITE_IOERR = 10
private const val SQLITE_CORRUPT = 11
private const val SQLITE_FULL = 13
private const val SQLITE_CANTOPEN = 14
private const val SQLITE_NOTADB = 26

/**
 * What [failure] says about the database. A cause counts as much as the failure itself, so a full
 * disk during an upgrade is [StorageProblem.DISK_FULL]: that is what the user can do something
 * about. A `SQLiteException` without a result code is what the driver throws when it cannot open
 * the file at all, so it is [StorageProblem.NO_ACCESS].
 */
public fun storageProblemOf(failure: Throwable): StorageProblem {
    val chain = generateSequence(failure) { it.cause }.take(MAX_CAUSES).toList()
    return chain.firstNotNullOfOrNull(::problemOfCode)
        ?: chain.firstNotNullOfOrNull(::problemOfType)
        ?: StorageProblem.OTHER
}

private const val MAX_CAUSES = 8

private fun problemOfCode(failure: Throwable): StorageProblem? =
    when (sqliteResultCode(failure)) {
        SQLITE_BUSY -> StorageProblem.LOCKED
        SQLITE_FULL -> StorageProblem.DISK_FULL
        SQLITE_CORRUPT, SQLITE_NOTADB -> StorageProblem.DAMAGED
        SQLITE_READONLY -> StorageProblem.READ_ONLY
        SQLITE_CANTOPEN, SQLITE_PERM -> StorageProblem.NO_ACCESS
        SQLITE_IOERR -> StorageProblem.DISK_ERROR
        else -> null
    }

private fun problemOfType(failure: Throwable): StorageProblem? =
    when {
        failure is StorageBusyException -> StorageProblem.LOCKED
        failure is DatabaseTooNewException -> StorageProblem.NEWER_VERSION
        failure is ForeignDatabaseException -> StorageProblem.NOT_JOTTER
        failure is DatabaseUpgradeException -> StorageProblem.UPGRADE_FAILED
        isSqliteError(failure) && sqliteResultCode(failure) == null -> StorageProblem.NO_ACCESS
        else -> null
    }
