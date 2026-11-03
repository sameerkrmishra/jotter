package io.github.sameerkrmishra.jotter.core.merge

import io.github.sameerkrmishra.jotter.core.merge.MergeChunk.Conflicting
import io.github.sameerkrmishra.jotter.core.merge.MergeChunk.Resolved
import io.github.sameerkrmishra.jotter.core.merge.MergeResult.Conflict
import kotlin.test.Test
import kotlin.test.assertEquals

class ConflictMarkersTest {
    @Test
    fun marksEachConflictAndKeepsTheRest() {
        val conflict =
            Conflict(listOf(Resolved("a\n"), Conflicting("b1\n", "b2\n"), Resolved("c")))

        assertEquals(
            "a\n<<<<<<< mine\nb1\n=======\nb2\n>>>>>>> yours\nc",
            conflict.withConflictMarkers("mine", "yours"),
        )
    }

    @Test
    fun anEmptySideHasNothingBetweenItsMarkers() {
        assertEquals(
            "<<<<<<< mine\n=======\nb\n>>>>>>> yours\n",
            Conflict(listOf(Conflicting("", "b\n"))).withConflictMarkers("mine", "yours"),
        )
    }

    @Test
    fun aMissingFinalBreakGetsOneBeforeTheMarker() {
        assertEquals(
            "<<<<<<< mine\na\n=======\nb\n>>>>>>> yours\n",
            Conflict(listOf(Conflicting("a", "b"))).withConflictMarkers("mine", "yours"),
        )
    }

    @Test
    fun aSideKeepsItsOwnLineBreaks() {
        assertEquals(
            "<<<<<<< mine\na\r\n=======\nb\r>>>>>>> yours\n",
            Conflict(listOf(Conflicting("a\r\n", "b\r"))).withConflictMarkers("mine", "yours"),
        )
    }
}
