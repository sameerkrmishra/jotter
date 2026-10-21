package io.github.sameerkrmishra.jotter.spike.fts5

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

/**
 * Minimal synchronous SQLDelight [SqlDriver] over one androidx [SQLiteConnection] (for example
 * from `BundledSQLiteDriver().open(path)`). Spike-quality: one connection, no statement cache,
 * no thread confinement. It exists to measure how much code our own adapter would need.
 */
public class ConnectionSqlDriver(private val connection: SQLiteConnection) : SqlDriver {
    private val listeners = mutableMapOf<String, MutableSet<Query.Listener>>()
    private var transaction: Tx? = null

    private inner class Tx(override val enclosingTransaction: Tx?) : Transacter.Transaction() {
        override fun endTransaction(successful: Boolean): QueryResult<Unit> {
            if (enclosingTransaction == null) {
                connection.execSQL(if (successful) "COMMIT" else "ROLLBACK")
            }
            transaction = enclosingTransaction
            return QueryResult.Unit
        }
    }

    override fun newTransaction(): QueryResult<Transacter.Transaction> {
        val outer = transaction
        if (outer == null) connection.execSQL("BEGIN IMMEDIATE")
        return QueryResult.Value(Tx(outer).also { transaction = it })
    }

    override fun currentTransaction(): Transacter.Transaction? = transaction

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> = connection.prepare(sql).use { stmt ->
        binders?.invoke(Binder(stmt))
        @Suppress("ControlFlowWithEmptyBody")
        while (stmt.step()) {
        }
        connection.prepare("SELECT changes()").use { it.step(); QueryResult.Value(it.getLong(0)) }
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> = connection.prepare(sql).use { stmt ->
        binders?.invoke(Binder(stmt))
        mapper(Cursor(stmt))
    }

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        queryKeys.forEach { listeners.getOrPut(it) { mutableSetOf() }.add(listener) }
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        queryKeys.forEach { listeners[it]?.remove(listener) }
    }

    override fun notifyListeners(vararg queryKeys: String) {
        queryKeys.flatMap { listeners[it].orEmpty() }.toSet().forEach { it.queryResultsChanged() }
    }

    override fun close(): Unit = connection.close()

    /** SQLDelight binds 0-based; SQLite binds 1-based. */
    private class Binder(private val stmt: SQLiteStatement) : SqlPreparedStatement {
        override fun bindBytes(index: Int, bytes: ByteArray?) =
            if (bytes == null) stmt.bindNull(index + 1) else stmt.bindBlob(index + 1, bytes)

        override fun bindLong(index: Int, long: Long?) =
            if (long == null) stmt.bindNull(index + 1) else stmt.bindLong(index + 1, long)

        override fun bindDouble(index: Int, double: Double?) =
            if (double == null) stmt.bindNull(index + 1) else stmt.bindDouble(index + 1, double)

        override fun bindString(index: Int, string: String?) =
            if (string == null) stmt.bindNull(index + 1) else stmt.bindText(index + 1, string)

        override fun bindBoolean(index: Int, boolean: Boolean?) =
            bindLong(index, boolean?.let { if (it) 1L else 0L })
    }

    private class Cursor(private val stmt: SQLiteStatement) : SqlCursor {
        override fun next(): QueryResult<Boolean> = QueryResult.Value(stmt.step())

        override fun getString(index: Int): String? = if (stmt.isNull(index)) null else stmt.getText(index)

        override fun getLong(index: Int): Long? = if (stmt.isNull(index)) null else stmt.getLong(index)

        override fun getBytes(index: Int): ByteArray? = if (stmt.isNull(index)) null else stmt.getBlob(index)

        override fun getDouble(index: Int): Double? = if (stmt.isNull(index)) null else stmt.getDouble(index)

        override fun getBoolean(index: Int): Boolean? = getLong(index)?.let { it != 0L }
    }
}
