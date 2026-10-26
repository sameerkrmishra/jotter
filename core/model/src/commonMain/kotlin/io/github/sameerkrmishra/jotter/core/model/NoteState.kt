package io.github.sameerkrmishra.jotter.core.model

/**
 * Where a note is shown. Merged last-writer-wins by [Stamp] (ADR-0003).
 *
 * There is no "deleted" state: "delete forever" writes a [RevisionContent.Tombstone] instead,
 * so that the deletion syncs like any other edit.
 */
public enum class NoteState {
    /** Shown in the main list. */
    ACTIVE,

    /** Hidden from the main list but kept. */
    ARCHIVED,

    /** In the trash. Nothing is purged automatically; the user deletes forever explicitly. */
    TRASHED,
}
