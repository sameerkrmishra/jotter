package io.github.sameerkrmishra.jotter.core.sqlite

import androidx.sqlite.SQLiteException

private const val SQLITE_BUSY = 5

/** The extended result codes (such as `SQLITE_BUSY_SNAPSHOT`) keep the primary one here. */
private const val PRIMARY_RESULT_CODE_MASK = 0xFF

/** The result code as androidx SQLite formats it, the same on every platform. */
private val RESULT_CODE = Regex("""^Error code: (\d{1,9}),""")

/**
 * Whether [error] is SQLite's `SQLITE_BUSY`: the database stayed locked by another connection,
 * usually in another process, for the whole `busy_timeout`, so the statement did not run and the
 * same call may work later.
 *
 * androidx `SQLiteException` has no property for the result code, but it writes the number into
 * its message. This reads that number rather than the text SQLite gives for it.
 */
public fun isSqliteBusy(error: Throwable): Boolean {
    val code = (error as? SQLiteException)?.message?.let(RESULT_CODE::find) ?: return false
    return code.groupValues[1].toInt() and PRIMARY_RESULT_CODE_MASK == SQLITE_BUSY
}
