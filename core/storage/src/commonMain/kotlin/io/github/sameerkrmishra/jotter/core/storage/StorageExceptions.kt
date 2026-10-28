package io.github.sameerkrmishra.jotter.core.storage

import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.RevisionId

/**
 * Thrown when a note does not exist: it never did, or it was deleted forever.
 *
 * @property noteId The id that was asked for.
 */
public class NoSuchNoteException(
    public val noteId: NoteId,
) : NoSuchElementException("No note with id $noteId")

/**
 * Thrown by [NoteService.edit] when the note changed since the editor read it: its current
 * revision is no longer the one the edit is based on, so writing the edit would silently drop the
 * other change to the text. The editor should reload the note and let the user redo the edit.
 *
 * This is a stopgap for M1, where a note has only one head. From M3 on, an edit on an older
 * revision becomes a branch in the note's revision DAG and is merged (ADR-0003).
 *
 * @property noteId The note.
 * @property basedOn The revision the edit was based on.
 * @property current The note's current revision.
 */
public class StaleEditException(
    public val noteId: NoteId,
    public val basedOn: RevisionId,
    public val current: RevisionId,
) : IllegalStateException(
        "Note $noteId changed: the edit is based on revision $basedOn, the current one is $current",
    )
