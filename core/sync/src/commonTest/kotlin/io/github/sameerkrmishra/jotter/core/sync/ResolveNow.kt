package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.RevisionId
import io.github.sameerkrmishra.jotter.core.storage.NoteStore

/** The note a result holds: null for [ResolveResult.Closed], and a failure if the head moved. */
internal fun ResolveResult.noteOrNull(): Note? =
    when (this) {
        is ResolveResult.Resolved -> note
        ResolveResult.Closed -> null
        is ResolveResult.ShownChanged -> error("The note shows another head: $shownHeadId")
    }

/**
 * Resolves the conflict of [mergeId] based on what the note shows now, as an answer the user gave
 * on an up-to-date screen. A closed conflict has no details, and then the head is not checked.
 */
internal fun ConflictResolver.resolveNow(
    mergeId: RevisionId,
    resolution: ConflictResolution,
): Note? = resolve(mergeId, resolution, details(mergeId)?.shownHeadId ?: mergeId).noteOrNull()

/** Settles the hidden head [headId] based on what its note in [store] shows now. */
internal fun ConflictResolver.settleNow(
    store: NoteStore,
    headId: RevisionId,
    resolution: ConflictResolution,
): Note? {
    val noteId = hidden().firstOrNull { it.revisionId == headId }?.noteId
    val shown = noteId?.let { store.notes.get(it)?.revisionId } ?: headId
    return settleHidden(headId, resolution, shown).noteOrNull()
}
