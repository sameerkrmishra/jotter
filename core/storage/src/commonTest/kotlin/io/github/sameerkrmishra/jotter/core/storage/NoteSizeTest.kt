package io.github.sameerkrmishra.jotter.core.storage

import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.NoteState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

class NoteSizeTest {
    private val atCap = "x".repeat(MAX_NOTE_CHARS)

    @Test
    fun aNewNoteAtTheCapIsStoredAndOneAboveItRefused() =
        withStore { store ->
            assertEquals(
                atCap.length,
                store.notes
                    .create("", atCap)
                    .body.length,
            )
            val error = assertFailsWith<NoteTooLargeException> { store.notes.create("t", atCap) }
            assertEquals(MAX_NOTE_CHARS + 1L, error.chars)
        }

    @Test
    fun anEditPastTheCapIsRefusedAndChangesNothing() =
        withStore { store ->
            val note = store.notes.create("", "small")
            assertFailsWith<NoteTooLargeException> {
                store.notes.edit(note.id, note.revisionId, "t", atCap)
            }
            assertEquals(note, store.notes.get(note.id))
        }

    @Test
    fun anImportPastTheCapIsReportedAndWritesNothing() =
        withStore { store ->
            val id = NoteId.generate()
            val time = Instant.fromEpochMilliseconds(1)
            val note =
                ImportedNote(id, "t", atCap, emptySet(), false, NoteState.ACTIVE, time, time)
            assertEquals(ImportResult.TOO_LARGE, store.notes.importNote(note))
            assertNull(store.notes.get(id))
        }
}
