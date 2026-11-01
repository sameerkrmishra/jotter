package io.github.sameerkrmishra.jotter.interop.keepimport

import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.NoteState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class KeepNoteMappingTest {
    @Test
    fun theIdIsTheUuid5OfTheCreatedTime() {
        // python3: uuid.uuid5(uuid.NAMESPACE_URL, "urn:jotter:google-keep:1488000000000000")
        val expected = NoteId.parse("2693d05a-07d9-5fa2-a27f-8aa58b71f569")

        assertEquals(expected, keepNoteId(1_488_000_000_000_000))
    }

    @Test
    fun differentCreatedTimesGiveDifferentIds() {
        assertNotEquals(keepNoteId(1_488_000_000_000_000), keepNoteId(1_488_000_000_000_001))
    }

    @Test
    fun listItemsBecomeATaskList() {
        val note =
            KeepNote(
                listContent = listOf(KeepListItem("milk", isChecked = true), KeepListItem("eggs")),
            )

        assertEquals("- [x] milk\n- [ ] eggs", note.markdownBody())
    }

    @Test
    fun lineBreaksInAListItemStayInTheItem() {
        val note = KeepNote(listContent = listOf(KeepListItem("one\ntwo\r\nthree")))

        assertEquals("- [ ] one\n  two\n  three", note.markdownBody())
    }

    @Test
    fun anEmptyListItemKeepsItsLine() {
        val note = KeepNote(listContent = listOf(KeepListItem(""), KeepListItem("x")))

        assertEquals("- [ ] \n- [ ] x", note.markdownBody())
    }

    @Test
    fun textComesBeforeAList() {
        val note = KeepNote(textContent = "Intro", listContent = listOf(KeepListItem("x")))

        assertEquals("Intro\n\n- [ ] x", note.markdownBody())
    }

    @Test
    fun textIsKeptAsItIs() {
        assertEquals("  # a\n\nb  ", KeepNote(textContent = "  # a\n\nb  ").markdownBody())
    }

    @Test
    fun anEmptyNoteHasAnEmptyBody() {
        assertEquals("", KeepNote().markdownBody())
    }

    @Test
    fun theTrashWinsOverTheArchive() {
        assertEquals(NoteState.ACTIVE, KeepNote().noteState())
        assertEquals(NoteState.ARCHIVED, KeepNote(isArchived = true).noteState())
        assertEquals(NoteState.TRASHED, KeepNote(isTrashed = true).noteState())
        assertEquals(NoteState.TRASHED, KeepNote(isArchived = true, isTrashed = true).noteState())
    }
}
