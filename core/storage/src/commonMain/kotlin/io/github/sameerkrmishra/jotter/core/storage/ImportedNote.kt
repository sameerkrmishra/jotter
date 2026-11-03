package io.github.sameerkrmishra.jotter.core.storage

import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.TagName
import kotlin.time.Instant

/**
 * A note from another app, as [NoteService.importNote] takes it.
 *
 * @property id The note's id, derived from the source note so that importing it again finds it
 *   (ADR-0009). It must not change between two exports of the same note.
 * @property title The title, possibly empty.
 * @property body The Markdown body.
 * @property tags The tags.
 * @property pinned Whether the note is pinned.
 * @property state The note's state.
 * @property createdAt When the note was created in the source. Only whole milliseconds count.
 * @property editedAt When the note was last edited in the source. Only whole milliseconds count.
 */
public data class ImportedNote(
    val id: NoteId,
    val title: String,
    val body: String,
    val tags: Set<TagName>,
    val pinned: Boolean,
    val state: NoteState,
    val createdAt: Instant,
    val editedAt: Instant,
)

/** What [NoteService.importNote] did with a note. */
public enum class ImportResult {
    /** The note is new and was stored. */
    IMPORTED,

    /** The note has a history here, possibly ending in deletion forever. Nothing was written. */
    SKIPPED,

    /** The note is over [MAX_NOTE_CHARS], so it could never sync. Nothing was written. */
    TOO_LARGE,
}
