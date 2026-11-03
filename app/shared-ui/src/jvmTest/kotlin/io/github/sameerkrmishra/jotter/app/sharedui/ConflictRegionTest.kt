package io.github.sameerkrmishra.jotter.app.sharedui

import io.github.sameerkrmishra.jotter.core.merge.MergeResult
import io.github.sameerkrmishra.jotter.core.merge.merge
import io.github.sameerkrmishra.jotter.core.merge.withConflictMarkers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConflictRegionTest {
    private fun marked(
        base: String,
        shown: String,
        other: String,
    ) = (merge(base, shown, other) as MergeResult.Conflict)
        .withConflictMarkers(SHOWN_MARKER_LABEL, OTHER_MARKER_LABEL)

    @Test
    fun linesAreCutAsCoreMergeCutsThem() {
        assertEquals(listOf("a\n", "b\r\n", "c\r", "d"), lines("a\nb\r\nc\rd"))
        assertEquals(listOf("a\r"), lines("a\r"))
        assertEquals(emptyList(), lines(""))
    }

    @Test
    fun theRegionsOfAConflictAreWhatAgreesAndWhatDiffers() {
        val shown = "eggs\noat milk\nbread\nbutter"
        val other = "eggs\nsoy milk\nbread\njam"

        val regions = regionsOf(marked("eggs\nmilk\nbread\nfat", shown, other), shown, other)

        assertEquals(
            listOf(
                ConflictRegion.Same("eggs\n"),
                ConflictRegion.Differs("oat milk\n", "soy milk\n"),
                ConflictRegion.Same("bread\n"),
                ConflictRegion.Differs("butter\n", "jam\n"),
            ),
            regions,
        )
    }

    @Test
    fun aLineEndingInACarriageReturnStaysApartFromTheMarker() {
        val shown = "a\rmine\r"
        val other = "a\rtheirs\r"

        val regions = regionsOf(marked("a\rbase\r", shown, other), shown, other)

        assertEquals(
            listOf(ConflictRegion.Same("a\r"), ConflictRegion.Differs("mine\r", "theirs\r")),
            regions,
        )
    }

    @Test
    fun aVersionWithALineLikeAMarkerGivesNoRegions() {
        listOf("=======", "<<<<<<< shown\r\n", ">>>>>>> other device").forEach { line ->
            val shown = "x\n$line"
            assertNull(regionsOf(marked("x\ny", shown, "x\nz"), shown, "x\nz"), line)
        }
    }

    @Test
    fun anUnfinishedConflictGivesNoRegions() {
        assertNull(regionsOf("a\n<<<<<<< shown\nb\n=======\nc\n", "a\nb", "a\nc"))
    }
}
