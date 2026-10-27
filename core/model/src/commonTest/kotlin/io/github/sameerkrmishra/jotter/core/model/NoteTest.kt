package io.github.sameerkrmishra.jotter.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Instant

class NoteTest {
    private val note =
        Note(
            id = sampleNote,
            title = "",
            body = "buy milk",
            tags = setOf(TagName.parse("Shopping")),
            isPinned = false,
            state = NoteState.ACTIVE,
            createdAt = Instant.fromEpochMilliseconds(1_000),
            updatedAt = HybridTimestamp(1_000, 0),
        )

    @Test
    fun notesAreComparedByValueWithNormalisedTags() {
        val sameNote = note.copy(tags = setOf(TagName.parse(" Shopping ")))

        assertEquals(note, sameNote)
        assertEquals(note.hashCode(), sameNote.hashCode())
    }

    @Test
    fun changingAFieldGivesADifferentNote() {
        val trashed = note.copy(state = NoteState.TRASHED)

        assertNotEquals(note, trashed)
        assertEquals(NoteState.ACTIVE, note.state)
    }
}
