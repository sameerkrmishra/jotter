package io.github.sameerkrmishra.jotter.core.sqlite

import androidx.sqlite.SQLiteStatement
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlPreparedStatement

/**
 * Binds SQLDelight parameters to an androidx [SQLiteStatement]. SQLDelight counts parameters from
 * 0, SQLite from 1. A null value binds SQL NULL.
 */
internal class StatementBinder(
    private val statement: SQLiteStatement,
) : SqlPreparedStatement {
    override fun bindBytes(
        index: Int,
        bytes: ByteArray?,
    ) = if (bytes == null) statement.bindNull(index + 1) else statement.bindBlob(index + 1, bytes)

    override fun bindLong(
        index: Int,
        long: Long?,
    ) = if (long == null) statement.bindNull(index + 1) else statement.bindLong(index + 1, long)

    override fun bindDouble(
        index: Int,
        double: Double?,
    ) = if (double == null) {
        statement.bindNull(index + 1)
    } else {
        statement.bindDouble(index + 1, double)
    }

    override fun bindString(
        index: Int,
        string: String?,
    ) = if (string == null) {
        statement.bindNull(index + 1)
    } else {
        statement.bindText(index + 1, string)
    }

    override fun bindBoolean(
        index: Int,
        boolean: Boolean?,
    ) = bindLong(index, boolean?.let { if (it) 1L else 0L })
}

/**
 * Reads the rows of an androidx [SQLiteStatement] for SQLDelight. Both count columns from 0.
 * A SQL NULL reads as null.
 */
internal class StatementCursor(
    private val statement: SQLiteStatement,
) : SqlCursor {
    override fun next(): QueryResult<Boolean> = QueryResult.Value(statement.step())

    override fun getString(index: Int): String? =
        if (statement.isNull(index)) null else statement.getText(index)

    override fun getLong(index: Int): Long? =
        if (statement.isNull(index)) null else statement.getLong(index)

    override fun getBytes(index: Int): ByteArray? =
        if (statement.isNull(index)) null else statement.getBlob(index)

    override fun getDouble(index: Int): Double? =
        if (statement.isNull(index)) null else statement.getDouble(index)

    override fun getBoolean(index: Int): Boolean? = getLong(index)?.let { it != 0L }
}
