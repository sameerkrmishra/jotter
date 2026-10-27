package io.github.sameerkrmishra.jotter.core.sqlite

import androidx.sqlite.SQLiteStatement

/**
 * A bounded, least-recently-used cache of prepared statements, keyed by the query identifier that
 * SQLDelight generates for every statement.
 *
 * A statement is *taken* out of the cache while it runs and *put* back afterwards, so a cached
 * statement is never in use, and two uses of the same identifier at once (a query run from inside
 * another query's mapper) never share one statement. Every statement that leaves the cache other
 * than through [take] (eviction, replacement, [closeAll]) is closed, which finalises it in SQLite.
 *
 * Not thread-safe: [ConnectionSqlDriver] only calls it while holding its connection lock.
 */
internal class StatementCache(
    private val capacity: Int,
) {
    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    // Kotlin's LinkedHashMap keeps insertion order. take() removes and put() re-inserts, so the
    // first key is always the least recently used one.
    private val statements = LinkedHashMap<Int, SQLiteStatement>()

    /** The number of cached statements. */
    val size: Int get() = statements.size

    /** Removes and returns the statement cached for [identifier], or null if there is none. */
    fun take(identifier: Int): SQLiteStatement? = statements.remove(identifier)

    /**
     * Caches [statement] for [identifier]. The caller must have reset it and cleared its bindings.
     * Closes any statement already cached for [identifier], and the least recently used statement
     * if the cache is then over capacity.
     */
    fun put(
        identifier: Int,
        statement: SQLiteStatement,
    ) {
        statements.put(identifier, statement)?.close()
        if (statements.size > capacity) {
            val (eldestIdentifier, eldest) = statements.entries.first()
            statements.remove(eldestIdentifier)
            eldest.close()
        }
    }

    /** Closes and removes every cached statement. */
    fun closeAll() {
        statements.values.forEach { it.close() }
        statements.clear()
    }
}
