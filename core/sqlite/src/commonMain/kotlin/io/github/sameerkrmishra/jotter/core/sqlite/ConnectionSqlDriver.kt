package io.github.sameerkrmishra.jotter.core.sqlite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

/**
 * How many prepared statements [ConnectionSqlDriver] keeps by default. Jotter's storage module has
 * a few dozen queries, so this keeps the frequently used ones prepared. A prepared statement costs
 * a few KB of native memory.
 */
internal const val STATEMENT_CACHE_SIZE = 32

/**
 * A synchronous SQLDelight [SqlDriver] over one androidx [SQLiteConnection]
 * (docs/adr/0002-sqlite-via-sqldelight.md). [openSqliteDriver] opens the connection and applies
 * the pragmas; this class only runs statements and transactions on it.
 *
 * ## Concurrency: one connection behind one mutex
 *
 * Every use of the connection holds [lock], so any thread may call the driver. Thread confinement
 * (one database thread that runs all statements) does not fit SQLDelight's synchronous API: a
 * transaction body runs on the caller's thread and issues its statements from there.
 *
 * A transaction holds [lock] from [newTransaction] to its [Transaction.endTransaction], on the
 * thread that opened it (SQLDelight checks that it ends on the same thread). Another thread's
 * statements therefore wait for the whole transaction, instead of landing inside it or seeing its
 * uncommitted rows. The lock is reentrant, so the transaction's own thread can still run
 * statements, nested transactions and [currentTransaction] while holding it.
 *
 * Invariant: when a thread holds [lock], [transaction] is either null or a transaction opened by
 * that same thread. This is what makes [currentTransaction] correct for every thread.
 *
 * A side effect: work on another thread that the transaction body waits for (for example a
 * `runBlocking` call into another dispatcher that queries the database) deadlocks. Keep
 * transaction bodies to database work.
 *
 * ## Listeners
 *
 * [listenerLock] guards only [listeners]. Lock order: [listenerLock] is never held while taking
 * [lock] or while calling a listener, and nothing takes [listenerLock] while running SQL, so the
 * two locks never nest and cannot deadlock.
 *
 * SQLDelight's generated code (`TransacterImpl`) defers notifications for writes made inside a
 * transaction and calls [notifyListeners] after [Transaction.endTransaction] has committed and
 * released [lock], so listeners only ever see committed data, and nothing at all on rollback.
 */
internal class ConnectionSqlDriver(
    private val connection: SQLiteConnection,
    statementCacheSize: Int,
) : SqlDriver {
    // Guards connection, statements, changeCounters, transaction and closed.
    private val lock = reentrantLock()
    private val statements = StatementCache(statementCacheSize)
    private val changeCounters = connection.prepare("SELECT changes(), total_changes()")
    private var transaction: Transaction? = null
    private var closed = false

    private val listenerLock = reentrantLock()
    private val listeners = mutableMapOf<String, MutableSet<Query.Listener>>()

    /**
     * Runs [sql] to completion and returns the number of rows it inserted, updated or deleted,
     * not counting changes made by triggers or foreign key actions. Other statements return 0.
     */
    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> =
        lock.withLock {
            checkOpen()
            val totalBefore = readChangeCounters().total
            useStatement(identifier, sql, binders) { statement ->
                while (statement.step()) {
                    // Stepping is the work. Rows (of a PRAGMA, or RETURNING) are not needed.
                }
            }
            val counters = readChangeCounters()
            // changes() keeps the count of the last INSERT, UPDATE or DELETE, so after a statement
            // that changed nothing (DDL, a PRAGMA, a SELECT) it is stale. total_changes() did not
            // move in that case.
            QueryResult.Value(if (counters.total == totalBefore) 0L else counters.changes)
        }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> =
        lock.withLock {
            checkOpen()
            useStatement(identifier, sql, binders) { mapper(StatementCursor(it)) }
        }

    /**
     * Starts a transaction, or a nested one inside the calling thread's open transaction. Only
     * the outermost one runs SQL (`BEGIN IMMEDIATE`). A nested transaction is part of the outer
     * one: SQLDelight ends the outer one unsuccessfully if any nested one failed, which rolls back
     * everything.
     *
     * `BEGIN IMMEDIATE` takes SQLite's write lock at once, so a second process waits for it
     * (up to `busy_timeout`) at the start instead of failing with `SQLITE_BUSY` when a read
     * transaction later tries to write. Read-only transactions take the write lock too; they are
     * short, and SQLDelight cannot tell them apart.
     */
    override fun newTransaction(): QueryResult<Transacter.Transaction> {
        // Taken once per transaction and released by its endTransaction(), not here.
        lock.lock()
        return cleanUpOnFailure(cleanUp = lock::unlock) {
            checkOpen()
            val enclosing = transaction
            if (enclosing == null) connection.execSQL("BEGIN IMMEDIATE")
            val newTransaction = Transaction(enclosing)
            transaction = newTransaction
            QueryResult.Value(newTransaction)
        }
    }

    /**
     * The calling thread's open transaction, or null. If another thread has a transaction open,
     * this waits until it has ended and then returns null: reading [transaction] without [lock]
     * could return the other thread's transaction, which SQLDelight would then join.
     */
    override fun currentTransaction(): Transacter.Transaction? = lock.withLock { transaction }

    override fun addListener(
        vararg queryKeys: String,
        listener: Query.Listener,
    ) {
        listenerLock.withLock {
            queryKeys.forEach { key -> listeners.getOrPut(key) { mutableSetOf() }.add(listener) }
        }
    }

    override fun removeListener(
        vararg queryKeys: String,
        listener: Query.Listener,
    ) {
        listenerLock.withLock {
            queryKeys.forEach { key ->
                val forKey = listeners[key] ?: return@forEach
                forKey.remove(listener)
                if (forKey.isEmpty()) listeners.remove(key)
            }
        }
    }

    /**
     * Tells every listener registered for any of [queryKeys] that its query's results changed.
     * Each listener is called once, even if it is registered for several of the keys. The
     * listeners are called after [listenerLock] is released, so they may add or remove listeners.
     */
    override fun notifyListeners(vararg queryKeys: String) {
        val toNotify =
            listenerLock.withLock {
                queryKeys.flatMapTo(mutableSetOf()) { key -> listeners[key].orEmpty() }
            }
        toNotify.forEach { it.queryResultsChanged() }
    }

    /**
     * Checkpoints the WAL into the database file, truncates it and closes the connection. When
     * this is the last connection to the file in any process, SQLite also deletes the `-wal` and
     * `-shm` files, so the database is one self-contained file again. Waits for transactions on
     * other threads to end. Calling it again does nothing.
     *
     * @throws IllegalStateException if called inside a transaction.
     */
    override fun close() {
        lock.withLock {
            if (closed) return
            check(transaction == null) { "Cannot close the database inside a transaction" }
            closed = true
            try {
                statements.closeAll()
                changeCounters.close()
                connection.prepare("PRAGMA wal_checkpoint(TRUNCATE)").use { it.step() }
            } finally {
                connection.close()
            }
        }
    }

    /**
     * Runs [block] on a prepared statement for [sql], bound by [binders]. Must be called with
     * [lock] held, on an open driver.
     *
     * A statement with an [identifier] comes from [statements] and goes back after a successful
     * run, reset and with its bindings cleared. If the run fails, the statement is closed instead,
     * so the cache only holds statements in a clean state. Statements without an identifier are
     * prepared for this call and closed after it, as SQLDelight requires.
     */
    private fun <T> useStatement(
        identifier: Int?,
        sql: String,
        binders: (SqlPreparedStatement.() -> Unit)?,
        block: (SQLiteStatement) -> T,
    ): T {
        val statement = identifier?.let(statements::take) ?: connection.prepare(sql)
        val result =
            cleanUpOnFailure(cleanUp = statement::close) {
                binders?.invoke(StatementBinder(statement))
                block(statement)
            }
        if (identifier == null) {
            statement.close()
        } else {
            // reset() only reports an error of the last step(), and every step succeeded.
            statement.reset()
            statement.clearBindings()
            statements.put(identifier, statement)
        }
        return result
    }

    private fun readChangeCounters(): ChangeCounters {
        changeCounters.step()
        val counters =
            ChangeCounters(changes = changeCounters.getLong(0), total = changeCounters.getLong(1))
        changeCounters.reset()
        return counters
    }

    private fun checkOpen() = check(!closed) { "The database is closed" }

    /** `changes()` and `total_changes()` of the connection at one point in time. */
    private class ChangeCounters(
        val changes: Long,
        val total: Long,
    )

    /**
     * One level of transaction. Only the outermost level (no [enclosingTransaction]) commits or
     * rolls back; nested levels only release their hold on [lock].
     */
    private inner class Transaction(
        override val enclosingTransaction: Transaction?,
    ) : Transacter.Transaction() {
        // SQLDelight calls this on the thread that opened the transaction, which holds [lock].
        override fun endTransaction(successful: Boolean): QueryResult<Unit> {
            try {
                if (enclosingTransaction == null) commitOrRollback(successful)
            } finally {
                transaction = enclosingTransaction
                lock.unlock()
            }
            return QueryResult.Unit
        }

        /**
         * Commits if [successful], otherwise rolls back. If COMMIT fails (for example on a
         * deferred foreign key violation) the transaction is rolled back and the error rethrown,
         * so the connection never stays inside a transaction.
         */
        private fun commitOrRollback(successful: Boolean) {
            if (successful) {
                cleanUpOnFailure(cleanUp = ::rollback) { connection.execSQL("COMMIT") }
            } else {
                rollback()
            }
        }

        private fun rollback() {
            // After some errors (SQLITE_FULL, SQLITE_IOERR, a ROLLBACK run by the caller) SQLite
            // has already rolled back, and ROLLBACK would fail.
            if (connection.inTransaction()) connection.execSQL("ROLLBACK")
        }
    }
}
