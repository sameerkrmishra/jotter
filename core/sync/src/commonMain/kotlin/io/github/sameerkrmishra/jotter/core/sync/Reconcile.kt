package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.Revision
import io.github.sameerkrmishra.jotter.core.model.RevisionContent
import io.github.sameerkrmishra.jotter.core.model.RevisionId
import io.github.sameerkrmishra.jotter.core.model.RevisionKind
import io.github.sameerkrmishra.jotter.core.storage.HiddenHead
import io.github.sameerkrmishra.jotter.core.storage.HiddenReason
import io.github.sameerkrmishra.jotter.core.storage.InvalidReason
import io.github.sameerkrmishra.jotter.core.storage.InvalidRevision
import io.github.sameerkrmishra.jotter.core.storage.OpenConflict
import io.github.sameerkrmishra.jotter.core.storage.SyncStore

/**
 * Returns the merges to make for the note's [revisions], in order: a left fold over the live
 * heads by id, which stops after a conflict (ADR-0003). Nothing while a conflict is open. A head
 * that the fold so far has come to cover, or that cannot be merged yet, is passed over.
 *
 * This is a function of the set of revisions, and that is why devices converge: two devices with
 * the same revisions make the same merges, whatever order the revisions arrived in. Every merge
 * has more user revisions than either parent, so with no new edits the merges come to an end.
 *
 * @param foreignParents See [RevisionGraph].
 */
internal fun foldHeads(
    revisions: List<Revision>,
    foreignParents: Set<RevisionId> = emptySet(),
): List<Revision> {
    val start = RevisionGraph(revisions, foreignParents)
    if (start.openConflicts.isNotEmpty()) return emptyList()
    val merges = mutableListOf<Revision>()
    var folded = start.liveHeads.firstOrNull()
    for (head in start.liveHeads.drop(1)) {
        val graph = RevisionGraph(revisions + merges, foreignParents)
        val current = checkNotNull(folded)
        if (!graph.isCoveredBy(head, current) && graph.canMerge(current, head)) {
            val merge = mergeRevisions(graph, current, head)
            merges += merge
            if (merge.isConflictedMerge()) break
            folded = merge
        }
    }
    return merges
}

/**
 * Brings the note [noteId] up to date with its stored revisions, inside the caller's transaction:
 * makes its merges, rewrites its open conflicts, hidden heads and invalid revisions, and
 * materialises the head it shows. Returns the merges it made.
 */
internal fun reconcile(
    store: SyncStore,
    noteId: NoteId,
): List<Revision> {
    val stored = store.revisions(noteId)
    val ids = stored.mapTo(HashSet()) { it.id }
    val foreign =
        stored
            .flatMapTo(HashSet()) { it.parents }
            .filterTo(HashSet()) { it !in ids && store.revision(it) != null }
    val merges = foldHeads(stored, foreign)
    merges.forEach(store::insert)
    val graph = RevisionGraph(stored + merges, foreign)
    store.replaceConflicts(noteId, graph.openConflicts.mapNotNull { openConflict(graph, it) })
    store.replaceHiddenHeads(noteId, graph.hiddenHeads.map { hiddenHead(graph, it) })
    store.replaceBadHistory(noteId, graph.invalidEnds.map(::invalidRow))
    val shown = graph.shownHead()
    if (shown != null) store.materialise(shown) else store.removeNote(noteId)
    return merges
}

private fun invalidRow(revision: Revision): InvalidRevision {
    val text = revision.content as RevisionContent.Snapshot
    return InvalidRevision(
        revision.id,
        revision.noteId,
        InvalidReason.BAD_HISTORY,
        text.title,
        text.body,
    )
}

private fun hiddenHead(
    graph: RevisionGraph,
    head: Revision,
): HiddenHead {
    val text = head.content as RevisionContent.Snapshot
    val waitsForConflict = graph.openConflicts.isNotEmpty()
    val reason = if (waitsForConflict) HiddenReason.OPEN_CONFLICT else HiddenReason.MISSING_ANCESTOR
    return HiddenHead(head.id, head.noteId, text.title, text.body, reason)
}

/** The conflict table's row for [merge], or null while its losing parent has not arrived. */
private fun openConflict(
    graph: RevisionGraph,
    merge: Revision,
): OpenConflict? {
    val loser = (merge.kind as RevisionKind.AutomaticMerge).losingParent?.let(graph::get)
    val text = loser?.content as? RevisionContent.Snapshot
    return text?.let { OpenConflict(merge.id, merge.noteId, loser.id, it.title, it.body) }
}
