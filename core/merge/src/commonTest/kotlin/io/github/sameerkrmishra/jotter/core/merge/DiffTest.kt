package io.github.sameerkrmishra.jotter.core.merge

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiffTest {
    private fun assertValid(
        base: IntArray,
        side: IntArray,
        hunks: List<Hunk>,
    ) {
        var baseLine = 0
        var sideLine = 0
        val rebuilt = ArrayList<Int>()
        hunks.forEachIndexed { index, hunk ->
            if (index > 0) assertTrue(hunk.baseStart > baseLine, "hunks must not touch: $hunks")
            assertEquals(hunk.baseStart - baseLine, hunk.sideStart - sideLine)
            rebuilt += base.slice(baseLine until hunk.baseStart)
            rebuilt += side.slice(hunk.sideStart until hunk.sideEnd)
            baseLine = hunk.baseEnd
            sideLine = hunk.sideEnd
        }
        rebuilt += base.slice(baseLine until base.size)

        assertEquals(side.toList(), rebuilt)
    }

    @Test
    fun equalInputsHaveNoHunks() {
        assertEquals(emptyList(), diff(intArrayOf(1, 2), intArrayOf(1, 2)))
        assertEquals(emptyList(), diff(intArrayOf(), intArrayOf()))
    }

    @Test
    fun anInsertionHasAnEmptyBaseRange() {
        assertEquals(listOf(Hunk(1, 1, 1, 2)), diff(intArrayOf(1, 2), intArrayOf(1, 9, 2)))
    }

    @Test
    fun aDeletionHasAnEmptySideRange() {
        assertEquals(listOf(Hunk(1, 2, 1, 1)), diff(intArrayOf(1, 9, 2), intArrayOf(1, 2)))
    }

    @Test
    fun anEmptyInputIsAllInsertedOrAllDeleted() {
        assertEquals(listOf(Hunk(0, 0, 0, 2)), diff(intArrayOf(), intArrayOf(1, 2)))
        assertEquals(listOf(Hunk(0, 2, 0, 0)), diff(intArrayOf(1, 2), intArrayOf()))
    }

    @Test
    fun aReplacementIsOneHunk() {
        assertEquals(listOf(Hunk(1, 2, 1, 3)), diff(intArrayOf(1, 2, 3), intArrayOf(1, 8, 9, 3)))
    }

    @Test
    fun theSearchIsExactUpToItsLimit() {
        val base = IntArray(MAX_EDITS / 2) { it }
        val side = IntArray(MAX_EDITS / 2) { it + MAX_EDITS }

        assertEquals(listOf(Hunk(0, base.size, 0, side.size)), diff(base, side))
    }

    @Test
    fun aLongCommonRunAfterTheLimitIsStillFound() {
        val rewritten = 2 * MAX_EDITS
        val common = IntArray(MAX_EDITS) { 2 * rewritten + it }
        val base = IntArray(rewritten) { it } + common + intArrayOf(-1)
        val side = IntArray(rewritten) { it + rewritten } + common + intArrayOf(-2)

        val hunks = diff(base, side)

        assertValid(base, side, hunks)
        assertTrue(hunks.size == 2, "expected the common run to separate the hunks: $hunks")
    }

    @Test
    fun hunksRebuildTheSide() =
        runTest {
            val inputs = Arb.list(Arb.element(0, 1, 2, 3), 0..30)
            checkAll(inputs, inputs) { base, side ->
                val baseIds = base.toIntArray()
                val sideIds = side.toIntArray()

                assertValid(baseIds, sideIds, diff(baseIds, sideIds))
            }
        }

    @Test
    fun hunksRebuildTheSideBeyondTheSearchLimit() =
        runTest {
            checkAll(
                iterations = 8,
                Arb.int(2..6),
                Arb.long(),
                Arb.long(),
            ) { variety, baseSeed, sideSeed ->
                val base = pseudorandomLines(2 * MAX_EDITS, variety, baseSeed)
                val side = pseudorandomLines(3 * MAX_EDITS, variety, sideSeed)

                assertValid(base, side, diff(base, side))
            }
        }
}
