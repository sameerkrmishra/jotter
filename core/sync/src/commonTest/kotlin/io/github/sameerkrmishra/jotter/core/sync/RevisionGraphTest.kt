package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.core.model.Revision
import io.github.sameerkrmishra.jotter.core.model.RevisionContent
import io.github.sameerkrmishra.jotter.core.model.RevisionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RevisionGraphTest {
    private val root = textRevision(1, 10, "root")
    private val left = textRevision(2, 20, "left", root)
    private val right = textRevision(3, 30, "right", root)

    @Test
    fun headsAreWhatNothingBuildsOnSortedById() {
        val graph = RevisionGraph(listOf(right, left, root))
        assertEquals(listOf(left, right), graph.heads)
        assertTrue(graph.isComplete)
    }

    @Test
    fun aMissingParentMakesTheGraphIncomplete() {
        assertFalse(RevisionGraph(listOf(left, right)).isComplete)
    }

    @Test
    fun theBaseOfTwoBranchesIsTheirForkPoint() {
        val graph = RevisionGraph(listOf(root, left, right))
        assertEquals(root, graph.mergeBase(left.id, right.id))
    }

    @Test
    fun aCrissCrossHasTwoBestAncestorsAndTheHighestStampIsTheBase() {
        val base = textRevision(1, 10, "a\nb\nc\nd\n")
        val a = textRevision(2, 20, "A\nb\nc\nd\n", base)
        val b = textRevision(3, 30, "a\nb\nc\nD\n", base, device = DEVICE_B)
        val a2 = textRevision(4, 40, "A\nB\nc\nd\n", a)
        val b2 = textRevision(5, 50, "a\nb\nC\nD\n", b, device = DEVICE_B)
        val all = listOf(base, a, b, a2, b2)
        val one = mergeRevisions(RevisionGraph(all), a2, b)
        val two = mergeRevisions(RevisionGraph(all), a, b2)
        val graph = RevisionGraph(all + listOf(one, two))
        assertEquals(setOf(a, b), graph.bestCommonAncestors(one.id, two.id).toSet())
        assertEquals(b, graph.mergeBase(one.id, two.id))
    }

    @Test
    fun theBaseDoesNotDependOnTheOrderOfThePairOrTheRevisions() {
        val x1 = textRevision(4, 40, "x", left)
        val x2 = textRevision(5, 41, "x", right)
        val crossed = listOf(root, left, right, x1, x2)
        val graph = RevisionGraph(crossed.reversed())
        assertEquals(graph.mergeBase(x1.id, x2.id), RevisionGraph(crossed).mergeBase(x2.id, x1.id))
    }

    @Test
    fun aWalkForABaseStopsAtATombstone() {
        val deleted =
            userRevision(4, 40, left) { RevisionContent.Tombstone }
        val backOne =
            mergeRevisions(RevisionGraph(listOf(root, left, right, deleted)), deleted, right)
        val other = textRevision(5, 41, "other", left)
        val backTwo =
            mergeRevisions(RevisionGraph(listOf(root, left, other, deleted)), deleted, other)
        val graph = RevisionGraph(listOf(root, left, right, other, deleted, backOne, backTwo))
        assertEquals(listOf(root), graph.bestCommonAncestors(backOne.id, backTwo.id))
    }

    @Test
    fun twoRootsHaveNoBase() {
        val other = textRevision(4, 15, "other root", device = DEVICE_B)
        val graph = RevisionGraph(listOf(root, other))
        assertNull(graph.mergeBase(root.id, other.id))
        assertEquals(setOf(root, other), graph.roots(root.id, other.id).toSet())
    }

    @Test
    fun aMergeCoveredByAnotherHeadDoesNotCount() {
        val edits = threeCleanEdits()
        val (a, b, c) = edits
        val small = mergeRevisions(RevisionGraph(edits), a, b)
        val other = mergeRevisions(RevisionGraph(edits), b, c)
        val big = mergeRevisions(RevisionGraph(edits + other), other, a)
        val graph = RevisionGraph(edits + listOf(small, other, big))
        assertEquals(setOf(small, big), graph.heads.toSet())
        assertEquals(listOf(big), graph.liveHeads)
    }

    @Test
    fun ofTwoMergesOfTheSameEditsTheLowerIdCounts() {
        val edits = threeCleanEdits()
        val (a, b, c) = edits
        val ab = mergeRevisions(RevisionGraph(edits), a, b)
        val ac = mergeRevisions(RevisionGraph(edits), a, c)
        val one = mergeRevisions(RevisionGraph(edits + ab), ab, c)
        val two = mergeRevisions(RevisionGraph(edits + ac), ac, b)
        val graph = RevisionGraph(edits + listOf(ab, ac, one, two))
        assertEquals(listOf(minOf(one, two, compareBy { it.id })), graph.liveHeads)
    }

    /** Three edits of different lines of one root, which comes last in the list. */
    private fun threeCleanEdits(): List<Revision> {
        val base = textRevision(1, 10, "a\n\nb\n\nc\n")
        val a = textRevision(2, 20, "A\n\nb\n\nc\n", base)
        val b = textRevision(3, 30, "a\n\nB\n\nc\n", base)
        val c = textRevision(4, 40, "a\n\nb\n\nC\n", base)
        return listOf(a, b, c, base)
    }

    @Test
    fun aUserHeadIsNeverCovered() {
        val merged = mergeRevisions(RevisionGraph(listOf(root, left, right)), left, right)
        val edit = textRevision(4, 40, "edit", root)
        val graph = RevisionGraph(listOf(root, left, right, merged, edit))
        assertEquals(setOf(merged, edit), graph.liveHeads.toSet())
    }

    @Test
    fun aConflictStaysOpenUntilAResolutionNamesIt() {
        val conflict = conflictOf(left, right)
        val all = listOf(root, left, right, conflict)
        assertEquals(listOf(conflict), RevisionGraph(all).openConflicts)
        val edit = textRevision(4, 40, "still open", conflict)
        assertEquals(listOf(conflict), RevisionGraph(all + edit).openConflicts)
        val resolution =
            userRevision(
                5,
                50,
                edit,
                resolves = conflict.id,
            ) { snapshot(body = "done", writer = it) }
        assertTrue(RevisionGraph(all + edit + resolution).openConflicts.isEmpty())
    }

    @Test
    fun aTombstoneBehindAConflictClosesIt() {
        val conflict = conflictOf(left, right)
        val deleted = userRevision(4, 40, conflict) { RevisionContent.Tombstone }
        assertTrue(
            RevisionGraph(listOf(root, left, right, conflict, deleted)).openConflicts.isEmpty(),
        )
    }

    @Test
    fun theNoteShowsTheHeadThatDescendsFromAnOpenConflict() {
        val conflict = conflictOf(left, right)
        val later = textRevision(4, 90, "newer, but beside the conflict", root)
        val graph = RevisionGraph(listOf(root, left, right, conflict, later))
        assertEquals(conflict, graph.shownHead())
        assertEquals(listOf(conflict), graph.headsDescendingFrom(conflict.id))
    }

    @Test
    fun theNoteShowsTheHeadWithTextRatherThanATombstone() {
        val deleted = userRevision(4, 90, left) { RevisionContent.Tombstone }
        assertEquals(right, RevisionGraph(listOf(left, right, deleted)).shownHead())
        assertEquals(deleted, RevisionGraph(listOf(root, left, deleted)).shownHead())
    }

    @Test
    fun anEmptyGraphShowsNothing() {
        assertNull(RevisionGraph(emptyList()).shownHead())
    }

    private fun conflictOf(
        a: Revision,
        b: Revision,
    ) = mergeRevisions(RevisionGraph(listOf(root, a, b)), a, b).also {
        assertTrue((it.kind as RevisionKind.AutomaticMerge).isConflicted)
    }
}
