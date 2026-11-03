package io.github.sameerkrmishra.jotter.core.model

import kotlin.time.Instant

/**
 * The current state of a note, as the UI and search see it (PLAN.md §4). It is materialised
 * from the note's head [Revision]; edits never change a [Note] directly but create revisions.
 * A note deleted forever has no [Note], only a [RevisionContent.Tombstone].
 *
 * @property id The note's identity.
 * @property revisionId The revision this note was materialised from. An editor passes it back
 *   with an edit, to say which revision the edit is based on.
 * @property title The title, possibly empty (as in Google Keep).
 * @property body The Markdown body.
 * @property tags The tags.
 * @property isPinned Whether the note is pinned to the top.
 * @property state Whether the note is active, archived or in the trash.
 * @property createdAt When the note was created: the [HybridTimestamp.physicalMillis] of its
 *   first revision. Like any HLC time it may be ahead of the creating device's wall clock,
 *   for example after that device synced with one whose clock runs ahead. For display only;
 *   it plays no part in merging.
 * @property updatedAt The timestamp of the revision [revisionId] names.
 */
public data class Note(
    val id: NoteId,
    val revisionId: RevisionId,
    val title: String,
    val body: String,
    val tags: Set<TagName>,
    val isPinned: Boolean,
    val state: NoteState,
    val createdAt: Instant,
    val updatedAt: HybridTimestamp,
)
