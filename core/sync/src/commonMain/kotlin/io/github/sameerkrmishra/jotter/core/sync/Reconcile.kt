package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.Revision
import io.github.sameerkrmishra.jotter.core.model.RevisionContent
import io.github.sameerkrmishra.jotter.core.model.RevisionKind
import io.github.sameerkrmishra.jotter.core.storage.OpenConflict
import io.github.sameerkrmishra.jotter.core.storage.SyncStore

/**
 * Returns the merges to make for the note's [revisions], in order: a left fold over the live
 * heads by id, which stops after a conflict (ADR-0003). Nothing while the ancestry is incomplete
 * or a conflict is open. A head that the fold so far has come to cover is passed over.
 *
 * This is a function of the set of revisions, and that is why devices converge: two devices with
 * the same revisions make the same merges, whatever order the revisions arrived in. Every merge
 * has more user revisions than either parent, so with no new edits the merges come to an end.
 */
internal fun foldHeads(revisions: List<Revision>): List<Revision> {
    val start = RevisionGraph(revisions)
    if (!start.isComplete || start.openConflicts.isNotEmpty()) return emptyList()
    val merges = mutableListOf<Revision>()
    var folded = start.liveHeads.firstOrNull()
    for (head in start.liveHeads.drop(1)) {
        val graph = RevisionGraph(revisions + merges)
        val current = checkNotNull(folded)
        if (!graph.isCoveredBy(head, current)) {
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
 * makes its merges, rewrites its open conflicts and materialises the head it shows. Returns the
 * merges it made.
 */
internal fun reconcile(
    store: SyncStore,
    noteId: NoteId,
): List<Revision> {
    val stored = store.revisions(noteId)
    val merges = foldHeads(stored)
    merges.forEach(store::insert)
    val graph = RevisionGraph(stored + merges)
    store.replaceConflicts(noteId, graph.openConflicts.mapNotNull { openConflict(graph, it) })
    graph.shownHead()?.let(store::materialise)
    return merges
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
