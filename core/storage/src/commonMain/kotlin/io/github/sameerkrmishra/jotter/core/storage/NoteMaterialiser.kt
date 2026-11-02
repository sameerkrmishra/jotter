package io.github.sameerkrmishra.jotter.core.storage

import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.Revision
import io.github.sameerkrmishra.jotter.core.model.RevisionContent
import io.github.sameerkrmishra.jotter.core.model.TagName
import io.github.sameerkrmishra.jotter.core.storage.db.NoteQueries

/**
 * Keeps the `note` and `note_tag` tables, which the UI and search read, in step with each note's
 * head revision.
 *
 * A user revision builds on the head the note shows, in the same transaction that materialises
 * it, so it is then the head to show. Computing heads and merging them is `core:sync`'s job.
 */
internal class NoteMaterialiser(
    private val queries: NoteQueries,
) {
    /**
     * Makes the note of [head] show [head]: writes its `note` row and `note_tag` rows, or deletes
     * them if [head] is a tombstone. Runs in one transaction, or as part of the caller's.
     *
     * [Note.createdAt] is the physical time of the note's earliest stored revision, which is its
     * first. It is recomputed every time, because in M3 an ancestor can arrive after its
     * descendants; it can then only move back. [Note.updatedAt] is [head]'s timestamp. Tags the
     * note keeps are not rewritten.
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
                    queries.deleteTagsExcept(noteId, content.tags.map(TagName::toString))
                    content.tags.forEach { tag -> queries.insertTag(noteId, tag.toString()) }
                }
            }
        }
    }
}
