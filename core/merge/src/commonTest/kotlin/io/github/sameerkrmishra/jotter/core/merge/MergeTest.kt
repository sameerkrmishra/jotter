package io.github.sameerkrmishra.jotter.core.merge

import io.github.sameerkrmishra.jotter.core.merge.MergeChunk.Conflicting
import io.github.sameerkrmishra.jotter.core.merge.MergeChunk.Resolved
import io.github.sameerkrmishra.jotter.core.merge.MergeResult.Clean
import io.github.sameerkrmishra.jotter.core.merge.MergeResult.Conflict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MergeTest {
    private fun assertClean(
        expected: String,
        base: String,
        ours: String,
        theirs: String,
    ) {
        assertEquals(Clean(expected), merge(base, ours, theirs))
        assertEquals(Clean(expected), merge(base, theirs, ours))
    }

    private fun assertConflict(
        base: String,
        ours: String,
        theirs: String,
    ) {
        assertIs<Conflict>(merge(base, ours, theirs))
        assertIs<Conflict>(merge(base, theirs, ours))
    }

    private val checklist = (1..6).joinToString("") { "- [ ] item $it\n" }

    @Test
    fun tickingOneItemAndAddingAnotherMerges() {
        val ticked = checklist.replace("[ ] item 3", "[x] item 3")
        val extended = checklist + "- [ ] item 7\n"

        assertClean(ticked + "- [ ] item 7\n", checklist, ticked, extended)
    }

    @Test
    fun tickingAndAddingMergeWhenTheNoteHasNoFinalNewline() {
        val base = checklist.removeSuffix("\n")
        val ticked = base.replace("[ ] item 3", "[x] item 3")
        val extended = "$base\n- [ ] item 7"

        assertClean("$ticked\n- [ ] item 7", base, ticked, extended)
    }

    @Test
    fun editsFarApartMerge() {
        assertClean("A\nb\nC\n", "a\nb\nc\n", "A\nb\nc\n", "a\nb\nC\n")
    }

    @Test
    fun differentEditsOfOneLineConflict() {
        assertEquals(
            Conflict(listOf(Resolved("a\n"), Conflicting("b1\n", "b2\n"), Resolved("c\n"))),
            merge("a\nb\nc\n", "a\nb1\nc\n", "a\nb2\nc\n"),
        )
    }

    @Test
    fun anEditAndADeleteOfOneLineConflict() {
        assertEquals(
            Conflict(listOf(Resolved("a\n"), Conflicting("b1\n", ""))),
            merge("a\nb\n", "a\nb1\n", "a\n"),
        )
    }

    @Test
    fun overlappingRangesConflictAsOneChunk() {
        assertEquals(
            Conflict(listOf(Conflicting("X\nc\nd\n", "a\nY\n"), Resolved("e\n"))),
            merge("a\nb\nc\nd\ne\n", "X\nc\nd\ne\n", "a\nY\ne\n"),
        )
    }

    @Test
    fun anInsertionInsideTheOtherSidesChangeConflicts() {
        assertConflict("a\nb\nc\nd\n", "a\nd\n", "a\nb\nnew\nc\nd\n")
    }

    @Test
    fun differentInsertionsAtOnePositionConflict() {
        assertEquals(
            Conflict(listOf(Resolved("a\n"), Conflicting("x\n", "y\n"), Resolved("b\n"))),
            merge("a\nb\n", "a\nx\nb\n", "a\ny\nb\n"),
        )
    }

    @Test
    fun anInsertionThatExtendsTheOtherAtOnePositionStillConflicts() {
        assertConflict("a\n", "a\nx\n", "a\nx\ny\n")
    }

    @Test
    fun identicalChangesMergeOnce() {
        assertClean("a\nB\nc\n", "a\nb\nc\n", "a\nB\nc\n", "a\nB\nc\n")
        assertClean("a\nnew\nb\n", "a\nb\n", "a\nnew\nb\n", "a\nnew\nb\n")
        assertClean("a\n", "a\nb\n", "a\n", "a\n")
    }

    @Test
    fun changesToAdjacentLinesMerge() {
        assertClean("A\nB\n", "a\nb\n", "A\nb\n", "a\nB\n")
    }

    @Test
    fun tickingAdjacentItemsMerges() {
        val base = "- [ ] one\n- [ ] two\n"

        assertClean(
            "- [x] one\n- [x] two\n",
            base,
            "- [x] one\n- [ ] two\n",
            "- [ ] one\n- [x] two\n",
        )
    }

    @Test
    fun anInsertionNextToTheOtherSidesChangeMergesInBaseOrder() {
        assertClean("new\nA\nb\n", "a\nb\n", "new\na\nb\n", "A\nb\n")
        assertClean("A\nnew\nb\n", "a\nb\n", "a\nnew\nb\n", "A\nb\n")
    }

    @Test
    fun aLineAddedOnBothSidesNextToAnEditIsAddedOnce() {
        val base = "- [ ] a\n- [ ] b\n"
        val ticked = "- [ ] a\n- [x] b\n- [ ] milk\n"
        val added = "- [ ] a\n- [ ] b\n- [ ] milk\n"

        assertClean(ticked, base, ticked, added)
    }

    @Test
    fun aLineAddedOnBothSidesBeforeAnEditIsAddedOnce() {
        assertClean("a\nnew\nB\nc\n", "a\nb\nc\n", "a\nnew\nB\nc\n", "a\nnew\nb\nc\n")
    }

    @Test
    fun severalLinesAddedOnBothSidesNextToAnEditAreAddedOnce() {
        assertClean("A\nx\ny\nb\n", "a\nb\n", "A\nx\ny\nb\n", "a\nx\ny\nb\n")
    }

    @Test
    fun theOrderOfATickAndTwoEqualAdditionsDoesNotDuplicateTheLine() {
        val base = "- [ ] a\n- [ ] b\n"
        val tickedAndAdded = "- [ ] a\n- [x] b\n- [ ] milk\n"
        val added = "- [ ] a\n- [ ] b\n- [ ] milk\n"

        val additionsFirst = assertIs<Clean>(merge(base, added, added)).text
        val tickFirst = assertIs<Clean>(merge(base, tickedAndAdded, added)).text

        assertEquals(Clean(tickedAndAdded), merge(base, additionsFirst, tickedAndAdded))
        assertEquals(Clean(tickedAndAdded), merge(base, tickFirst, added))
    }

    @Test
    fun anInsertionMatchingTheFarEndOfTheChangeIsKept() {
        assertClean("new\nA\nnew\nb\n", "a\nb\n", "new\nA\nb\n", "a\nnew\nb\n")
    }

    @Test
    fun anInsertionThatOnlyPartlyRepeatsTheChangeIsKeptWhole() {
        assertClean("A\nx\nx\ny\nb\n", "a\nb\n", "A\nx\nb\n", "a\nx\ny\nb\n")
    }

    @Test
    fun aLineAddedByTwoReplacementsAppearsTwice() {
        assertClean(
            "a\nB\nmilk\nmilk\nC\n",
            "a\nb\nc\n",
            "a\nB\nmilk\nc\n",
            "a\nb\nmilk\nC\n",
        )
    }

    @Test
    fun aLastLineWithoutABreakAddedOnBothSidesIsAddedOnce() {
        assertClean("a\nB\nx", "a\nb\n", "a\nB\nx", "a\nb\nx")
    }

    @Test
    fun aDeletionNextToAnInsertionMerges() {
        assertClean("new\nb\n", "a\nb\n", "b\n", "a\nnew\nb\n")
    }

    @Test
    fun aDeletionOfAllTheLinesNextToAnInsertionMerges() {
        assertClean("new\n", "a\n", "", "a\nnew\n")
    }

    @Test
    fun aMergeWithAConflictKeepsTheCleanPartsOfBothSides() {
        assertEquals(
            Conflict(
                listOf(
                    Resolved("A\nb\n"),
                    Conflicting("c1\n", "c2\n"),
                    Resolved("d\nE\n"),
                ),
            ),
            merge("a\nb\nc\nd\ne\n", "A\nb\nc1\nd\ne\n", "a\nb\nc2\nd\nE\n"),
        )
    }

    @Test
    fun aConflictMirrorsWhenTheSidesSwap() {
        assertEquals(
            Conflict(listOf(Conflicting("x\n", "y\n"))),
            merge("a\n", "x\n", "y\n"),
        )
        assertEquals(
            Conflict(listOf(Conflicting("y\n", "x\n"))),
            merge("a\n", "y\n", "x\n"),
        )
    }

    @Test
    fun anEmptyBaseMergesEqualTextsAndConflictsOnDifferentOnes() {
        assertClean("same\n", "", "same\n", "same\n")
        assertClean("text", "", "", "text")
        assertEquals(
            Conflict(listOf(Conflicting("one\nsame\n", "two\nsame\n"))),
            merge("", "one\nsame\n", "two\nsame\n"),
        )
    }

    @Test
    fun allEmptyIsCleanAndEmpty() {
        assertClean("", "", "", "")
    }

    @Test
    fun oneSideEmptiedAndTheOtherUntouchedGivesEmpty() {
        assertClean("", "a\nb\n", "", "a\nb\n")
    }

    @Test
    fun bothSidesEmptiedGivesEmpty() {
        assertClean("", "a\nb\n", "", "")
    }

    @Test
    fun oneSideEmptiedAndTheOtherEditedConflicts() {
        assertEquals(
            Conflict(listOf(Conflicting("", "a\nB\n"))),
            merge("a\nb\n", "", "a\nB\n"),
        )
    }

    @Test
    fun singleLineTitlesMergeLikeBodies() {
        assertClean("Trip", "Plan", "Trip", "Plan")
        assertConflict("Plan", "Trip", "Tour")
    }

    @Test
    fun differentLineBreaksKeepTheirOwnLines() {
        assertClean("A\r\nb\rC\n", "a\r\nb\rc\n", "A\r\nb\rc\n", "a\r\nb\rC\n")
    }

    @Test
    fun aLineWithAnotherBreakIsAChangedLine() {
        assertClean("a\r\nb\n", "a\nb\n", "a\r\nb\n", "a\nb\n")
        assertConflict("a\n", "a\r\n", "A\n")
    }

    @Test
    fun bothSidesChangingTheBreaksTheSameWayMerge() {
        assertClean("a\r\nb\r\n", "a\nb\n", "a\r\nb\r\n", "a\r\nb\r\n")
    }

    @Test
    fun theFinalLineKeepsItsMissingBreak() {
        assertClean("A\nb", "a\nb", "A\nb", "a\nb")
    }

    @Test
    fun removingAndAddingTheFinalBreakAreChanges() {
        assertClean("A\nb", "a\nb\n", "A\nb\n", "a\nb")
        assertClean("A\nb\n", "a\nb", "A\nb", "a\nb\n")
    }

    @Test
    fun aLineIsNotJoinedToTheOneAddedAfterItsLostBreak() {
        // Line for line this merges to "a\n", "b", "c\n", which reads as "a\nbc\n".
        assertEquals(
            Conflict(listOf(Resolved("a\n"), Conflicting("b", "b\nc\n"))),
            merge("a\nb\n", "a\nb", "a\nb\nc\n"),
        )
    }

    @Test
    fun aLoneCrIsNotJoinedToALineThatIsOnlyLf() {
        // Line for line this merges to "q\r", "\n", which would read as the single line "q\r\n".
        assertConflict("p\ny\n\n", "q\ry\n\n", "p\n\n")
    }

    @Test
    fun touchingChangesConflictEverywhereOnceJoiningThemWouldAlterALine() {
        val base = "a\nb\nunchanged\nc\nd\n"
        val ours = "A\nb\nunchanged\nc\nd"
        val theirs = "a\nB\nunchanged\nc\nd\ne\n"

        assertEquals(
            Conflict(
                listOf(
                    Conflicting("A\nb\n", "a\nB\n"),
                    Resolved("unchanged\nc\n"),
                    Conflicting("d", "d\ne\n"),
                ),
            ),
            merge(base, ours, theirs),
        )
    }

    @Test
    fun nonBmpLinesMergeWhole() {
        assertClean(
            "😀 a\nb\n😁 c\n",
            "😀\nb\n😁\n",
            "😀 a\nb\n😁\n",
            "😀\nb\n😁 c\n",
        )
    }

    @Test
    fun unpairedSurrogatesSurvive() {
        assertClean("\uDE00\nb\n\uD83D", "a\nb\n\uD83D", "\uDE00\nb\n\uD83D", "a\nb\n\uD83D")
    }

    @Test
    fun nothingDependsOnUnicodeLineSeparators() {
        assertClean("X b\nC\n", "a b\nc\n", "X b\nc\n", "a b\nC\n")
    }
}
