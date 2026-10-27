package io.github.sameerkrmishra.jotter.core.model

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HybridTimestampTest {
    @Test
    fun ordersByMillisThenCounter() {
        assertTrue(HybridTimestamp(1, Int.MAX_VALUE) < HybridTimestamp(2, 0))
        assertTrue(HybridTimestamp(2, 0) < HybridTimestamp(2, 1))
        assertEquals(0, HybridTimestamp(2, 1).compareTo(HybridTimestamp(2, 1)))
    }

    @Test
    fun rejectsNegativeParts() {
        assertFailsWith<IllegalArgumentException> { HybridTimestamp(-1, 0) }
        assertFailsWith<IllegalArgumentException> { HybridTimestamp(0, -1) }
    }

    @Test
    fun successorIncrementsTheCounter() {
        assertEquals(HybridTimestamp(5, 1), HybridTimestamp(5, 0).successor())
    }

    @Test
    fun successorMovesToTheNextMillisecondWhenTheCounterIsFull() {
        assertEquals(HybridTimestamp(6, 0), HybridTimestamp(5, Int.MAX_VALUE).successor())
    }

    @Test
    fun nothingFollowsTheGreatestTimestamp() {
        val error = assertFailsWith<IllegalArgumentException> { HybridTimestamp.MAX.successor() }

        assertEquals(
            "No timestamp follows ${HybridTimestamp.MAX}; it is likely corrupt",
            error.message,
        )
        assertEquals(
            HybridTimestamp(Long.MAX_VALUE, 0),
            HybridTimestamp(Long.MAX_VALUE - 1, Int.MAX_VALUE).successor(),
        )
    }

    @Test
    fun orderIsTotalAndConsistentWithEquals() = runTest { checkTotalOrderLaws(timestamps) }
}
