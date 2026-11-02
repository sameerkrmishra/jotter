package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.Revision
import io.github.sameerkrmishra.jotter.core.model.RevisionContent
import io.github.sameerkrmishra.jotter.core.model.RevisionKind
import io.github.sameerkrmishra.jotter.core.model.Stamped
import io.github.sameerkrmishra.jotter.core.model.TagName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoteMergeTest {
    private fun merged(
        all: List<Revision>,
        a: Revision,
        b: Revision,
    ) = mergeRevisions(RevisionGraph(all), a, b)

    private val base = textRevision(1, 10, "- [ ] a\n- [ ] b\n- [ ] c")

    @Test
    fun editsOfDifferentLinesMerge() {
        val phone = textRevision(2, 20, "- [x] a\n- [ ] b\n- [ ] c", base, device = DEVICE_B)
        val pc = textRevision(3, 30, "- [ ] a\n- [ ] b\n- [x] c", base)
        val merge = merged(listOf(base, phone, pc), phone, pc)
        assertEquals("- [x] a\n- [ ] b\n- [x] c", merge.text.body)
        assertEquals(RevisionKind.AutomaticMerge(null), merge.kind)
    }

    @Test
    fun aMergeHasTheStampOfTheGreaterParentAndNoDeviceOfItsOwn() {
        val phone = textRevision(2, 20, "x\n- [ ] b\n- [ ] c", base, device = DEVICE_B)
        val pc = textRevision(3, 30, "- [ ] a\n- [ ] b\ny", base)
        val merge = merged(listOf(base, phone, pc), phone, pc)
        assertEquals(pc.timestamp, merge.timestamp)
        assertEquals(pc.deviceId, merge.deviceId)
        assertEquals(setOf(phone.id, pc.id), merge.parents)
    }

    @Test
    fun theOrderOfTheParentsChangesNothing() {
        val phone = textRevision(2, 20, "x", base, tags = setOf("t"), device = DEVICE_B)
        val pc = textRevision(3, 30, "y", base, tags = setOf("u"))
        val all = listOf(base, phone, pc)
        assertEquals(merged(all, phone, pc), merged(all, pc, phone))
    }

    @Test
    fun aConflictKeepsTheHigherStampedTextAndNamesTheLoser() {
        val phone = textRevision(2, 20, "phone", base, device = DEVICE_B)
        val pc = textRevision(3, 30, "pc", base)
        val merge = merged(listOf(base, phone, pc), phone, pc)
        assertEquals("pc", merge.text.body)
        assertEquals(RevisionKind.AutomaticMerge(phone.id), merge.kind)
    }

    @Test
    fun aConflictInTheTitleTakesTheBodyFromTheWinnerToo() {
        val root = userRevision(1, 10) { snapshot("t", "line 1\nline 2", writer = it) }
        val one = userRevision(2, 20, root) { snapshot("one", "edit\nline 2", writer = it) }
        val two = userRevision(3, 30, root) { snapshot("two", "line 1\nline 2 edit", writer = it) }
        val merge = merged(listOf(root, one, two), one, two)
        assertEquals("two", merge.text.title)
        assertEquals("line 1\nline 2 edit", merge.text.body)
        assertEquals(one.id, (merge.kind as RevisionKind.AutomaticMerge).losingParent)
    }

    @Test
    fun aCleanMergeThatCouldNotBeStoredIsAConflict() {
        val root = textRevision(1, 10, "first\nsecond\nthird")
        val one = textRevision(2, 20, "﻿second\nthird", root)
        val two = textRevision(3, 30, "first\n﻿second\nchanged", root)
        val merge = merged(listOf(root, one, two), one, two)
        assertEquals(one.id, (merge.kind as RevisionKind.AutomaticMerge).losingParent)
        assertEquals(two.text.body, merge.text.body)
    }

    @Test
    fun tagsAddedOnEitherSideStayAndRemovedOnesGo() {
        val root = textRevision(1, 10, "", tags = setOf("keep", "gone"))
        val one = textRevision(2, 20, "", root, tags = setOf("keep", "gone", "new"))
        val two = textRevision(3, 30, "", root, tags = setOf("keep"))
        val merge = merged(listOf(root, one, two), one, two)
        assertEquals(
            setOf("keep", "new"),
            merge.text.tags
                .map(TagName::toString)
                .toSet(),
        )
    }

    @Test
    fun anAddBeatsAConcurrentRemove() {
        val root = textRevision(1, 10, "", tags = setOf("t"))
        val removed = textRevision(2, 20, "", root, tags = emptySet())
        val alsoRemoved = textRevision(3, 25, "", root, tags = emptySet())
        val addedBack = textRevision(4, 30, "", alsoRemoved, tags = setOf("t"))
        val merge = merged(listOf(root, removed, alsoRemoved, addedBack), removed, addedBack)
        assertEquals(
            setOf("t"),
            merge.text.tags
                .map(TagName::toString)
                .toSet(),
        )
    }

    @Test
    fun aMergeDoesNotCountAsAddingATag() {
        val root = textRevision(1, 10, "a", tags = setOf("t"))
        val removed = textRevision(2, 20, "b", root, tags = emptySet())
        val edited = textRevision(3, 30, "a", root, tags = setOf("t"))
        val merge = merged(listOf(root, removed, edited), removed, edited)
        assertEquals(emptySet(), merge.text.tags)
    }

    @Test
    fun withoutACommonAncestorTagsAreTheirUnion() {
        val one = textRevision(1, 10, "text", tags = setOf("a"))
        val two = textRevision(2, 10, "text", tags = setOf("b"), device = DEVICE_B)
        val merge = merged(listOf(one, two), one, two)
        assertEquals(
            setOf("a", "b"),
            merge.text.tags
                .map(TagName::toString)
                .toSet(),
        )
    }

    @Test
    fun pinAndStateCarryTheWinnersOriginalStamp() {
        val root = textRevision(1, 10, "text")
        val pinned =
            userRevision(2, 40, root) { root.text.copy(pinned = Stamped(true, it)) }
        val archived =
            userRevision(3, 30, root, DEVICE_B) {
                root.text.copy(state = Stamped(NoteState.ARCHIVED, it))
            }
        val edit = userRevision(4, 50, archived, DEVICE_B) { archived.text.copy(body = "edited") }
        val merge = merged(listOf(root, pinned, archived, edit), pinned, edit)
        assertTrue(merge.text.pinned.value)
        assertEquals(pinned.stamp, merge.text.pinned.stamp)
        assertEquals(NoteState.ARCHIVED, merge.text.state.value)
        assertEquals(archived.stamp, merge.text.state.stamp)
    }

    @Test
    fun anEditBeatsADeletionAndTheNoteComesBackInTheTrash() {
        val edit = textRevision(2, 20, "edited", base, tags = setOf("t"))
        val deleted = userRevision(3, 30, base, DEVICE_B) { RevisionContent.Tombstone }
        val merge = merged(listOf(base, edit, deleted), edit, deleted)
        assertEquals("edited", merge.text.body)
        assertEquals(
            setOf("t"),
            merge.text.tags
                .map(TagName::toString)
                .toSet(),
        )
        assertEquals(Stamped(NoteState.TRASHED, deleted.stamp), merge.text.state)
        assertEquals(deleted.timestamp, merge.timestamp)
        assertEquals(merge, merged(listOf(base, edit, deleted), deleted, edit))
    }

    @Test
    fun twoDeletionsMergeIntoADeletion() {
        val one = userRevision(2, 20, base) { RevisionContent.Tombstone }
        val two = userRevision(3, 30, base, DEVICE_B) { RevisionContent.Tombstone }
        val merge = merged(listOf(base, one, two), one, two)
        assertEquals(RevisionContent.Tombstone, merge.content)
        assertFalse((merge.kind as RevisionKind.AutomaticMerge).isConflicted)
    }

    @Test
    fun rootsThatAgreeGiveTheirTextAsBase() {
        val one = textRevision(1, 10, "line 1\nline 2\n")
        val two = textRevision(2, 10, "line 1\nline 2\n", device = DEVICE_B)
        val edit = textRevision(3, 20, "line 1 edited\nline 2\n", one)
        val merge = merged(listOf(one, two, edit), edit, two)
        assertEquals("line 1 edited\nline 2\n", merge.text.body)
        assertNull((merge.kind as RevisionKind.AutomaticMerge).losingParent)
    }

    @Test
    fun rootsThatDifferMergeAgainstNothing() {
        val one = textRevision(1, 10, "same\nmine")
        val two = textRevision(2, 10, "same\nyours", device = DEVICE_B)
        val merge = merged(listOf(one, two), one, two)
        assertEquals(
            one.id.coerceAtMost(two.id),
            (merge.kind as RevisionKind.AutomaticMerge).losingParent,
        )
    }

    @Test
    fun theFoldStopsAtTheFirstConflict() {
        val conflicting = (2..4).map { textRevision(it, it * 10L, "edit $it", base) }
        val merges = foldHeads(listOf(base) + conflicting)
        assertEquals(1, merges.size)
        assertTrue(merges.single().isConflictedMerge())
        val paused = listOf(base) + conflicting + merges
        assertTrue(foldHeads(paused).isEmpty())
    }

    @Test
    fun theFoldLeavesAnIncompleteNoteAlone() {
        val edits = (2..3).map { textRevision(it, it * 10L, "edit $it", base) }
        assertTrue(foldHeads(edits).isEmpty())
    }

    @Test
    fun aFoldEndsInOneHeadAndAnArrivalOrderCannotChangeIt() {
        val lines = listOf("a\nb\nc\nd\n", "A\nb\nc\nd\n", "a\nB\nc\nd\n", "a\nb\nc\nD\n")
        val root = textRevision(1, 10, lines[0])
        val edits = (1..3).map { textRevision(it + 1, it * 10L + 10, lines[it], root) }
        val all = listOf(root) + edits
        val merges = foldHeads(all)
        assertEquals(2, merges.size)
        assertEquals(merges, foldHeads(all.reversed()))
        assertEquals(1, RevisionGraph(all + merges).liveHeads.size)
        assertEquals("A\nB\nc\nD\n", merges.last().text.body)
    }

    private fun <T : Comparable<T>> T.coerceAtMost(other: T): T = minOf(this, other)
}
