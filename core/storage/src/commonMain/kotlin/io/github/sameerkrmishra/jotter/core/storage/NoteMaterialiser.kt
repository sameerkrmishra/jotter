package io.github.sameerkrmishra.jotter.core.storage

import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.Revision
import io.github.sameerkrmishra.jotter.core.model.RevisionContent
import io.github.sameerkrmishra.jotter.core.storage.db.NoteQueries

/**
 * Keeps the `note` and `note_tag` tables, which the UI and search read, in step with each note's
 * head revision.
 *
 * In M1 every note has exactly one head: a user revision always builds on the current head, in
 * the same transaction that materialises it. Computing heads and merging them is M3's job.
 */
internal class NoteMaterialiser(
    private val queries: NoteQueries,
) {
    /**
     * Makes the note of [head] show [head]: writes its `note` row and `note_tag` rows, or deletes
     * them if [head] is a tombstone. Runs in one transaction, or as part of the caller's.
     *
     * A new row's [Note.createdAt] is the physical time of the note's earliest stored revision,
     * which is its first; an existing row keeps its own. [Note.updatedAt] is [head]'s timestamp.
     *
     * [head] must already be stored, as the `note` row refers to it.
     */
    fun materialise(head: Revision) {
        val noteId = head.noteId.toString()
        queries.transaction {
            when (val content = head.content) {
                RevisionContent.Tombstone -> {
                    queries.deleteNote(noteId)
                }

                is RevisionContent.Snapshot -> {
                    queries.upsertNote(
                        id = noteId,
                        revisionId = head.id.toString(),
                        title = content.title,
                        body = content.body,
                        isPinned = content.pinned.value,
                        state = content.state.value.name,
                        updatedPhysical = head.timestamp.physicalMillis,
                        updatedCounter = head.timestamp.counter.toLong(),
                    )
                    queries.deleteTags(noteId)
                    content.tags.forEach { tag -> queries.insertTag(noteId, tag.toString()) }
                }
            }
        }
    }
}
