package io.github.sameerkrmishra.jotter.core.merge

import io.github.sameerkrmishra.jotter.core.merge.MergeResult.Clean
import io.github.sameerkrmishra.jotter.core.merge.MergeResult.Conflict
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ITERATIONS = 2_000

class MergePropertyTest {
    private fun mirrored(result: MergeResult): MergeResult =
        when (result) {
            is Clean -> {
                result
            }

            is Conflict -> {
                Conflict(
                    result.chunks.map { chunk ->
                        when (chunk) {
                            is MergeChunk.Resolved -> chunk
                            is MergeChunk.Conflicting ->
                                MergeChunk.Conflicting(chunk.theirs, chunk.ours)
                        }
                    },
                )
            }
        }

    @Test
    fun anUntouchedOursGivesTheirs() =
        runTest {
            checkAll(ITERATIONS, texts, texts) { a, b -> assertEquals(Clean(b), merge(a, a, b)) }
        }

    @Test
    fun anUntouchedTheirsGivesOurs() =
        runTest {
            checkAll(ITERATIONS, texts, texts) { a, b -> assertEquals(Clean(b), merge(a, b, a)) }
        }

    @Test
    fun identicalSidesGiveThatSide() =
        runTest {
            checkAll(ITERATIONS, texts, texts) { a, b -> assertEquals(Clean(b), merge(a, b, b)) }
        }

    @Test
    fun nothingChangedGivesTheBase() =
        runTest {
            checkAll(ITERATIONS, texts) { a -> assertEquals(Clean(a), merge(a, a, a)) }
        }

    @Test
    fun swappingTheSidesMirrorsTheResult() =
        runTest {
            checkAll(ITERATIONS, relatedTexts) { (base, ours, theirs) ->
                assertEquals(mirrored(merge(base, ours, theirs)), merge(base, theirs, ours))
            }
        }

    @Test
    fun swappingUnrelatedSidesMirrorsTheResult() =
        runTest {
            checkAll(ITERATIONS, texts, texts, texts) { base, ours, theirs ->
                assertEquals(mirrored(merge(base, ours, theirs)), merge(base, theirs, ours))
            }
        }

    @Test
    fun aCleanMergeOnlyHoldsLinesFromTheInputs() =
        runTest {
            checkAll(ITERATIONS, relatedTexts) { (base, ours, theirs) ->
                val merged = merge(base, ours, theirs)
                if (merged is Clean) {
                    val known = splitLines(base) + splitLines(ours) + splitLines(theirs)
                    assertTrue(known.containsAll(splitLines(merged.text)), "$merged")
                }
            }
        }

    @Test
    fun touchingEditsNeverAlterALine() =
        runTest {
            checkAll(ITERATIONS, touchingEdits) { (base, ours, theirs) ->
                val merged = merge(base, ours, theirs)
                if (merged is Clean) {
                    val known = splitLines(base) + splitLines(ours) + splitLines(theirs)
                    assertTrue(known.containsAll(splitLines(merged.text)), "$merged")
                }
            }
        }

    @Test
    fun touchingEditsMirrorWhenTheSidesSwap() =
        runTest {
            checkAll(ITERATIONS, touchingEdits) { (base, ours, theirs) ->
                assertEquals(mirrored(merge(base, ours, theirs)), merge(base, theirs, ours))
            }
        }

    @Test
    fun touchingEditsKeepTheLaws() =
        runTest {
            checkAll(ITERATIONS, touchingEdits) { (base, ours, _) ->
                assertEquals(Clean(ours), merge(base, base, ours))
                assertEquals(Clean(ours), merge(base, ours, base))
                assertEquals(Clean(ours), merge(base, ours, ours))
            }
        }

    @Test
    fun linesBothSidesAddedNextToAnEditAreAddedOnce() =
        runTest {
            checkAll(ITERATIONS, repeatedInsertions) { edits ->
                assertEquals(
                    Clean(edits.expected),
                    merge(edits.base, edits.edited, edits.inserted),
                )
                assertEquals(
                    Clean(edits.expected),
                    merge(edits.base, edits.inserted, edits.edited),
                )
            }
        }

    @Test
    fun nonOverlappingEditsAlwaysMergeToBothEdits() =
        runTest {
            checkAll(ITERATIONS, disjointEdits) { edits ->
                assertEquals(Clean(edits.merged), merge(edits.base, edits.ours, edits.theirs))
                assertEquals(Clean(edits.merged), merge(edits.base, edits.theirs, edits.ours))
            }
        }

    @Test
    fun conflictMarkersHoldBothSidesOfAConflict() =
        runTest {
            checkAll(ITERATIONS, relatedTexts) { (base, ours, theirs) ->
                val merged = merge(base, ours, theirs)
                if (merged is Conflict) {
                    val marked = merged.withConflictMarkers("mine", "yours")
                    merged.chunks.filterIsInstance<MergeChunk.Conflicting>().forEach {
                        assertTrue(it.ours in marked && it.theirs in marked, marked)
                    }
                }
            }
        }
}
