package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.core.merge.MergeResult
import io.github.sameerkrmishra.jotter.core.merge.merge
import io.github.sameerkrmishra.jotter.core.merge.withConflictMarkers
import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.Revision
import io.github.sameerkrmishra.jotter.core.model.RevisionContent
import io.github.sameerkrmishra.jotter.core.model.RevisionId
import io.github.sameerkrmishra.jotter.core.storage.HiddenHead
import io.github.sameerkrmishra.jotter.core.storage.HiddenReason
import io.github.sameerkrmishra.jotter.core.storage.InvalidRevision
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import io.github.sameerkrmishra.jotter.core.storage.OpenConflict
import io.github.sameerkrmishra.jotter.core.storage.requireNoteSize
import io.github.sameerkrmishra.jotter.core.storage.toStorableText
import kotlinx.coroutines.flow.Flow

/**
 * What the conflict screen shows for one open conflict.
 *
 * @property conflict The conflict, with the text that lost.
 * @property shownTitle The title the note shows now.
 * @property shownBody The body the note shows now.
 * @property markedTitle The shown and losing titles merged, with conflict markers if they conflict,
 *   for "edit merged text".
 * @property markedBody The same for the body.
 */
public data class ConflictDetails(
    val conflict: OpenConflict,
    val shownTitle: String,
    val shownBody: String,
    val markedTitle: String,
    val markedBody: String,
)

/** The user's answer to a conflict (PLAN.md §5). */
public sealed interface ConflictResolution {
    /** Keeps the text the note shows. */
    public data object KeepShown : ConflictResolution

    /** Replaces the text with the text that lost. */
    public data object KeepLosing : ConflictResolution

    /** Keeps the text the note shows, and makes a new note of the text that lost. */
    public data object KeepBoth : ConflictResolution

    /**
     * Replaces the text with the user's.
     *
     * @property title The new title.
     * @property body The new body.
     */
    public data class Edited(
        val title: String,
        val body: String,
    ) : ConflictResolution
}

/**
 * Lists open conflicts and resolves them. All functions block on the database, like `NoteService`.
 * Resolving writes a user revision and syncs like any other; another device that resolved the same
 * conflict makes a second head, which merges as usual.
 */
@Suppress("TooManyFunctions") // What the conflict screen does, kept together on purpose.
public class ConflictResolver(
    private val store: NoteStore,
) {
    /** Returns the open conflicts, by note. */
    public fun open(): List<OpenConflict> = store.sync.conflicts()

    /** Emits [open] now and again whenever it changes. */
    public fun observeOpen(): Flow<List<OpenConflict>> = store.sync.observeConflicts()

    /** Returns what the conflict screen needs for the merge [mergeId], or null if it is closed. */
    public fun details(mergeId: RevisionId): ConflictDetails? {
        val conflict = store.sync.conflict(mergeId) ?: return null
        val graph = storedGraph(store.sync, conflict.noteId)
        return if (graph.isOpen(mergeId)) detailsOf(conflict, graph) else null
    }

    private fun detailsOf(
        conflict: OpenConflict,
        graph: RevisionGraph,
    ): ConflictDetails {
        val shown = graph.snapshotOfHead(conflict.mergeId)
        val (loser, winner) = graph.parentsOf(conflict.mergeId, conflict.losingRevisionId)
        val title = graph.baseText(winner, loser) { it.title }
        val body = graph.baseText(winner, loser) { it.body }
        return ConflictDetails(
            conflict = conflict,
            shownTitle = shown.title,
            shownBody = shown.body,
            markedTitle = marked(title, shown.title, conflict.losingTitle),
            markedBody = marked(body, shown.body, conflict.losingBody),
        )
    }

    /**
     * Closes the conflict of the merge [mergeId] with [resolution], and merges the note's other
     * heads, which waited for this, in one transaction. Returns the note, or null if a merge with a
     * deletion took it away.
     *
     * @throws NoSuchConflictException if the conflict is not open.
     */
    public fun resolve(
        mergeId: RevisionId,
        resolution: ConflictResolution,
    ): Note? =
        store.sync.write {
            val conflict = store.sync.conflict(mergeId) ?: throw NoSuchConflictException(mergeId)
            val graph = storedGraph(store.sync, conflict.noteId)
            if (graph.isOpen(mergeId)) {
                val losing = LosingText(conflict.losingTitle, conflict.losingBody)
                settle(graph.headOf(mergeId), mergeId, losing, resolution)
            } else {
                // A deletion closed it, and the row waits for the next sync. Tidy up now.
                reconcile(store.sync, conflict.noteId)
                store.notes.get(conflict.noteId)
            }
        }

    /** Returns the heads that a note does not show until a merge can run, by note. */
    public fun hidden(): List<HiddenHead> = store.sync.hiddenHeads()

    /** Emits [hidden] now and again whenever it changes. */
    public fun observeHidden(): Flow<List<HiddenHead>> = store.sync.observeHiddenHeads()

    /**
     * Deals with the hidden head [headId], with no merge: [ConflictResolution.KeepLosing] takes its
     * text in place of the shown text, and the others work as in [resolve]. This is the way out
     * when a missing ancestor will never come (ADR-0014). Other devices stop counting that head,
     * and the note merges as far as it can. A head that waits for an open conflict cannot be
     * settled, as that would pass over the conflict: resolve the conflict.
     *
     * @throws NoSuchHiddenHeadException if [headId] is not hidden.
     * @throws HiddenHeadWaitsForConflictException if an open conflict holds it back.
     */
    public fun settleHidden(
        headId: RevisionId,
        resolution: ConflictResolution,
    ): Note? =
        store.sync.write {
            val hidden =
                store.sync.hiddenHeads().firstOrNull { it.revisionId == headId }
                    ?: throw NoSuchHiddenHeadException(headId)
            if (hidden.reason == HiddenReason.OPEN_CONFLICT) {
                throw HiddenHeadWaitsForConflictException(headId)
            }
            val graph = storedGraph(store.sync, hidden.noteId)
            val parent = checkNotNull(graph.shownHead()) { "A hidden head has a shown one" }
            settle(parent, headId, LosingText(hidden.title, hidden.body), resolution)
        }

    /** Returns text that the DAG does not count, by note, so that it is not lost in silence. */
    public fun invalid(): List<InvalidRevision> = store.sync.invalidRevisions()

    /** Emits [invalid] now and again whenever it changes. */
    public fun observeInvalid(): Flow<List<InvalidRevision>> = store.sync.observeInvalidRevisions()

    /** Writes the user revision on [parent] that names [closed]. Part of a transaction. */
    private fun settle(
        parent: Revision,
        closed: RevisionId,
        losing: LosingText,
        resolution: ConflictResolution,
    ): Note? {
        val shown = parent.content as RevisionContent.Snapshot
        val content =
            when (resolution) {
                ConflictResolution.KeepShown -> {
                    shown
                }

                ConflictResolution.KeepLosing -> {
                    shown.copy(title = losing.title, body = losing.body)
                }

                ConflictResolution.KeepBoth -> {
                    store.notes.create(losing.title, losing.body, shown.tags)
                    shown
                }

                is ConflictResolution.Edited -> {
                    requireNoteSize(resolution.title, resolution.body)
                    val title = resolution.title.toStorableText()
                    shown.copy(title = title, body = resolution.body.toStorableText())
                }
            }
        store.sync.insertResolution(parent, content, closed)
        reconcile(store.sync, parent.noteId)
        return store.notes.get(parent.noteId)
    }

    private fun marked(
        base: String,
        shown: String,
        losing: String,
    ): String =
        when (val result = merge(base, shown, losing)) {
            is MergeResult.Clean -> result.text
            is MergeResult.Conflict -> result.withConflictMarkers(SHOWN_LABEL, LOSING_LABEL)
        }

    private companion object {
        const val SHOWN_LABEL = "shown"
        const val LOSING_LABEL = "other device"
    }
}

/**
 * The conflict of the merge [mergeId] is not open: it was closed, or never existed.
 *
 * @property mergeId The merge.
 */
public class NoSuchConflictException(
    public val mergeId: RevisionId,
) : IllegalArgumentException("Merge $mergeId has no open conflict")

/**
 * The revision [headId] is not a hidden head: it was merged or settled, or never was one.
 *
 * @property headId The revision.
 */
public class NoSuchHiddenHeadException(
    public val headId: RevisionId,
) : IllegalArgumentException("Revision $headId is not a hidden head")

/**
 * The hidden head [headId] merges once a conflict is resolved, so it cannot be settled.
 *
 * @property headId The revision.
 */
public class HiddenHeadWaitsForConflictException(
    public val headId: RevisionId,
) : IllegalArgumentException("Revision $headId waits for an open conflict")

private class LosingText(
    val title: String,
    val body: String,
)

/** Whether the revisions say the conflict is open. The `conflict` row can lag behind a deletion. */
private fun RevisionGraph.isOpen(mergeId: RevisionId): Boolean =
    openConflicts.any { it.id == mergeId }

/** The head a resolution of [mergeId] builds on. An open conflict always has one with text. */
private fun RevisionGraph.headOf(mergeId: RevisionId): Revision =
    headsDescendingFrom(mergeId).first { it.content is RevisionContent.Snapshot }

private fun RevisionGraph.snapshotOfHead(mergeId: RevisionId): RevisionContent.Snapshot =
    headOf(mergeId).content as RevisionContent.Snapshot

/** The winning parent's and the losing parent's revisions, losing first. */
private fun RevisionGraph.parentsOf(
    mergeId: RevisionId,
    loserId: RevisionId,
): Pair<Revision, Revision> {
    val parents = checkNotNull(this[mergeId]).parents
    val loser = checkNotNull(this[loserId])
    return loser to checkNotNull(this[parents.first { it != loserId }])
}
