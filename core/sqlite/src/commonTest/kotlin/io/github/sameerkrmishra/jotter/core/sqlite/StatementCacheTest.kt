package io.github.sameerkrmishra.jotter.core.sqlite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Uses real statements: a closed (finalised) statement throws when it is used. */
class StatementCacheTest {
    private val dir = TempDatabaseDir()
    private val connection: SQLiteConnection = openRawConnection(dir.path)

    @AfterTest
    fun cleanUp() {
        connection.close()
        dir.close()
    }

    @Test
    fun takeRemovesTheStatement() {
        val cache = StatementCache(capacity = 2)
        val statement = prepare()
        cache.put(1, statement)

        assertSame(statement, cache.take(1))
        assertNull(cache.take(1))
        assertNull(cache.take(2))
        assertUsable(statement)
    }

    @Test
    fun leastRecentlyUsedStatementIsEvictedAndClosed() {
        val cache = StatementCache(capacity = 2)
        val first = prepare()
        val second = prepare()
        val third = prepare()
        cache.put(1, first)
        cache.put(2, second)
        // Using 1 makes 2 the least recently used.
        cache.put(1, checkNotNull(cache.take(1)))
        cache.put(3, third)

        assertEquals(2, cache.size)
        assertClosed(second)
        assertSame(first, cache.take(1))
        assertSame(third, cache.take(3))
    }

    @Test
    fun replacedStatementIsClosed() {
        val cache = StatementCache(capacity = 2)
        val old = prepare()
        val new = prepare()
        cache.put(1, old)
        cache.put(1, new)

        assertEquals(1, cache.size)
        assertClosed(old)
        assertSame(new, cache.take(1))
    }

    @Test
    fun closeAllClosesAndForgetsEveryStatement() {
        val cache = StatementCache(capacity = 2)
        val statements = listOf(prepare(), prepare())
        statements.forEachIndexed { identifier, statement -> cache.put(identifier, statement) }

        cache.closeAll()

        assertEquals(0, cache.size)
        statements.forEach { assertClosed(it) }
    }

    @Test
    fun capacityMustBePositive() {
        assertFailsWith<IllegalArgumentException> { StatementCache(capacity = 0) }
    }

    private fun prepare(): SQLiteStatement = connection.prepare("SELECT 1")

    private fun assertUsable(statement: SQLiteStatement) {
        assertTrue(statement.step())
        statement.reset()
    }

    private fun assertClosed(statement: SQLiteStatement) {
        assertFailsWith<SQLiteException> { statement.step() }
    }
}
