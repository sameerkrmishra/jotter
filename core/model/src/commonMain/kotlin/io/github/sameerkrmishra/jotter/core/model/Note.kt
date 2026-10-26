package io.github.sameerkrmishra.jotter.core.model

import kotlin.time.Instant

/**
 * The current state of a note, as the UI and search see it (PLAN.md §4). It is materialised
 * from the note's head [Revision]; edits never change a [Note] directly but create revisions.
 * A note deleted forever has no [Note], only a [RevisionContent.Tombstone].
 *
 * @property id The note's identity.
 * @property title The title, possibly empty (as in Google Keep).
 * @property body The Markdown body.
 * @property tags The tags.
 * @property isPinned Whether the note is pinned to the top.
 * @property state Whether the note is active, archived or in the trash.
 * @property createdAt When the note was created, by the creating device's wall clock. For
 *   display only; it plays no part in merging.
 * @property updatedAt The timestamp of the revision the note was materialised from.
 */
public data class Note(
    val id: NoteId,
    val title: String,
    val body: String,
    val tags: Set<TagName>,
    val isPinned: Boolean,
    val state: NoteState,
    val createdAt: Instant,
    val updatedAt: HybridTimestamp,
)
