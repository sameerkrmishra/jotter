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
 * Thrown by [NoteService.edit] when the note's text changed since the editor read it: the title
 * or body of the revision the edit is based on differ from the current ones (or that revision is
 * not one of this note's), so writing the edit would silently drop the other change. Tag, pin
 * and state changes in between do not cause it, nor does saving the text the note already has.
 * The editor should reload the note and let the user redo the edit.
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
        "The text of note $noteId changed since revision $basedOn; the current one is $current",
    )

/**
 * Thrown when a write found the database locked by another process, such as the CLI during a long
 * write, for the whole 5 s it waits for the lock. Nothing was written; the write may be tried
 * again.
 *
 * @param cause The SQLite driver's error.
 */
public class StorageBusyException(
    cause: Throwable,
) : IllegalStateException("The database stayed locked by another process", cause)
