package io.github.sameerkrmishra.jotter.core.merge

import io.github.sameerkrmishra.jotter.core.merge.MergeResult.Clean
import io.github.sameerkrmishra.jotter.core.merge.MergeResult.Conflict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val LINES = 100_000

/**
 * The bodies of these tests are far larger than a note. Nothing here looks at a clock, because
 * that fails on a slow machine and proves little: the tests count the steps of the diff instead.
 */
class LargeBodyTest {
    /** Linear in the input, with [MAX_EDITS] as the factor; quadratic would be 10^10 steps. */
    private val stepLimit = 4L * LINES * MAX_EDITS

    private fun stepsOf(
        base: IntArray,
        side: IntArray,
    ): Long = DiffCost().also { diff(base, side, it) }.steps

    @Test
    fun textsWithNothingInCommonStayBelowTheLimit() {
        val base = IntArray(LINES) { it }
        val side = IntArray(LINES) { it + LINES }

        assertTrue(stepsOf(base, side) < stepLimit)
    }

    @Test
    fun aShuffleOfAllTheLinesStaysBelowTheLimit() {
        val base = IntArray(LINES) { it }
        val side = pseudorandomLines(LINES, Int.MAX_VALUE, seed = 1)

        assertTrue(stepsOf(base, base.sortedBy { side[it] }.toIntArray()) < stepLimit)
    }

    @Test
    fun manyRepeatedLinesStayBelowTheLimit() {
        val base = pseudorandomLines(LINES, variety = 2, seed = 2)
        val side = pseudorandomLines(LINES, variety = 2, seed = 3)

        assertTrue(stepsOf(base, side) < stepLimit)
    }

    @Test
    fun oneRepeatedLineAgainstAnotherLengthStaysBelowTheLimit() {
        assertTrue(stepsOf(IntArray(LINES), IntArray(LINES / 2)) < stepLimit)
    }

    @Test
    fun fewEditsAreCheap() {
        val base = IntArray(LINES) { it }
        val side = base.copyOf().also { it[LINES / 2] = -1 }

        assertTrue(stepsOf(base, side) < 2L * LINES)
    }

    @Test
    fun editsFarApartInAHugeBodyMerge() {
        val base = IntArray(LINES) { it }.asText()
        val ours = base.replace("line 12345\n", "line 12345 edited\n")
        val theirs = base.replace("line 87654\n", "line 87654 edited\n") + "added\n"

        val expected =
            base
                .replace("line 12345\n", "line 12345 edited\n")
                .replace("line 87654\n", "line 87654 edited\n") + "added\n"

        assertEquals(Clean(expected), merge(base, ours, theirs))
    }

    @Test
    fun manyEditsInAHugeBodyMergeBeyondTheSearchLimit() {
        val base = (0 until LINES).map { "line $it\n" }
        val ours = base.mapIndexed { index, line -> if (index % 10 == 0) "ours $index\n" else line }
        val theirs =
            base.mapIndexed { index, line -> if (index % 10 == 5) "theirs $index\n" else line }
        val expected =
            base.mapIndexed { index, line ->
                when (index % 10) {
                    0 -> ours[index]
                    5 -> theirs[index]
                    else -> line
                }
            }

        assertEquals(
            Clean(expected.joinToString("")),
            merge(base.joinToString(""), ours.joinToString(""), theirs.joinToString("")),
        )
    }

    @Test
    fun aHugeBodyRewrittenOnBothSidesConflicts() {
        val base = IntArray(LINES) { it }.asText()
        val ours = IntArray(LINES) { it + LINES }.asText()
        val theirs = IntArray(LINES) { it + 2 * LINES }.asText()

        val merged = assertIs<Conflict>(merge(base, ours, theirs))

        assertEquals(listOf(MergeChunk.Conflicting(ours, theirs)), merged.chunks)
    }

    /**
     * Pins how a diff beyond the search limit cuts the text, on every platform this test runs on.
     * An automatic merge's id hashes the merged text, so a change here changes the sync format.
     */
    @Test
    fun theMergedTextOfAHardMergeIsPinned() {
        val base = pseudorandomLines(LINES / 4, variety = 8, seed = 4)
        val ours = base.copyOf().also { lines -> lines.indices.step(7).forEach { lines[it] = 8 } }
        val theirs = pseudorandomLines(LINES / 4, variety = 8, seed = 5)

        val merged = assertIs<Conflict>(merge(base.asText(), ours.asText(), theirs.asText()))

        assertEquals(
            PINNED_CHECKSUM,
            checksum(merged.withConflictMarkers("ours", "theirs")),
        )
    }

    private companion object {
        const val PINNED_CHECKSUM = 2_638_666_930_055_098_695L
    }
}
