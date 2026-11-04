package io.github.sameerkrmishra.jotter.core.sqlite

private const val SQLITE_BUSY = 5

/**
 * Whether [error] is SQLite's `SQLITE_BUSY`: the database stayed locked by another connection,
 * usually in another process, for the whole `busy_timeout`, so the statement did not run and the
 * same call may work later. Read by [sqliteResultCode].
 */
public fun isSqliteBusy(error: Throwable): Boolean = sqliteResultCode(error) == SQLITE_BUSY
