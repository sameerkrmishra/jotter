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
import io.github.sameerkrmishra.jotter.core.storage.ResolutionDraft
import io.github.sameerkrmishra.jotter.core.storage.requireNoteSize
import io.github.sameerkrmishra.jotter.core.storage.toStorableText
import kotlinx.coroutines.flow.Flow

/**
 * What the conflict screen shows for one open conflict.
 *
 * @property conflict The conflict, with the text that lost.
 * @property shownHeadId The head whose text the note shows now, which a resolution builds on. Pass
 *   it to [ConflictResolver.resolve], so that a newer edit is not overwritten unseen.
 * @property shownTitle The title the note shows now.
 * @property shownBody The body the note shows now.
 * @property markedTitle The shown and losing titles merged, with conflict markers if they conflict,
 *   for "edit merged text".
 * @property markedBody The same for the body.
 */
public data class ConflictDetails(
    val conflict: OpenConflict,
    val shownHeadId: RevisionId,
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

/** What [ConflictResolver.resolve] and [ConflictResolver.settleHidden] did. */
public sealed interface ResolveResult {
    /**
     * The answer was written as one revision.
     *
     * @property note The note, or null if a merge with a deletion took it away.
     */
    public data class Resolved(
        val note: Note?,
    ) : ResolveResult

    /**
     * Nothing was written: the note shows another version than the one the answer was based on,
     * such as an edit made meanwhile. Show it, and answer again.
     *
     * @property shownHeadId The head the note shows now.
     */
    public data class ShownChanged(
        val shownHeadId: RevisionId,
    ) : ResolveResult

    /** Nothing was written: a deletion beside it closed the conflict already. */
    public data object Closed : ResolveResult
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
    init {
        keepNotesSettled(store)
    }

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
        val head = graph.headOf(conflict.mergeId)
        val shown = head.content as RevisionContent.Snapshot
        val (loser, winner) = graph.parentsOf(conflict.mergeId, conflict.losingRevisionId)
        val title = graph.baseText(winner, loser) { it.title }
        val body = graph.baseText(winner, loser) { it.body }
        return ConflictDetails(
            conflict = conflict,
            shownHeadId = head.id,
            shownTitle = shown.title,
            shownBody = shown.body,
            markedTitle = marked(title, shown.title, conflict.losingTitle),
            markedBody = marked(body, shown.body, conflict.losingBody),
        )
    }

    /**
     * Closes the conflict of the merge [mergeId] with [resolution], and merges the note's other
     * heads, which waited for this, in one transaction. A draft for [mergeId] goes with it.
     *
     * [expectedHead] is [ConflictDetails.shownHeadId] as the user saw it. If the note shows another
     * head by now, nothing is written and the result is [ResolveResult.ShownChanged]: keeping the
     * other version, or a text combined from the old one, would drop that newer edit unseen.
     *
     * @throws NoSuchConflictException if the conflict is not open.
     */
    public fun resolve(
        mergeId: RevisionId,
        resolution: ConflictResolution,
        expectedHead: RevisionId,
    ): ResolveResult =
        store.sync.write {
            val conflict = store.sync.conflict(mergeId) ?: throw NoSuchConflictException(mergeId)
            val graph = storedGraph(store.sync, conflict.noteId)
            if (graph.isOpen(mergeId)) {
                val losing = LosingText(conflict.losingTitle, conflict.losingBody)
                val head = graph.headOf(mergeId)
                answer(head, expectedHead) { settle(head, mergeId, losing, resolution) }
            } else {
                // A deletion closed it, and the row waits for the next sync. Tidy up now.
                reconcile(store.sync, conflict.noteId)
                ResolveResult.Closed
            }
        }

    /** Returns the heads that a note does not show until a merge can run, by note. */
    public fun hidden(): List<HiddenHead> = store.sync.hiddenHeads()

    /** Emits [hidden] now and again whenever it changes. */
    public fun observeHidden(): Flow<List<HiddenHead>> = store.sync.observeHiddenHeads()

    /**
     * Deals with the hidden head [headId], with no merge, as [resolve] does with [expectedHead]
     * the head the note shows (`Note.revisionId`): [ConflictResolution.KeepLosing] takes its
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
        expectedHead: RevisionId,
    ): ResolveResult =
        store.sync.write {
            val hidden =
                store.sync.hiddenHeads().firstOrNull { it.revisionId == headId }
                    ?: throw NoSuchHiddenHeadException(headId)
            if (hidden.reason == HiddenReason.OPEN_CONFLICT) {
                throw HiddenHeadWaitsForConflictException(headId)
            }
            val graph = storedGraph(store.sync, hidden.noteId)
            val parent = checkNotNull(graph.shownHead()) { "A hidden head has a shown one" }
            answer(parent, expectedHead) {
                settle(parent, headId, LosingText(hidden.title, hidden.body), resolution)
            }
        }

    /**
     * Returns text that the DAG does not count, by note, so that it is not lost in silence, but
     * for what the user dismissed on this device.
     */
    public fun invalid(): List<InvalidRevision> = store.sync.shownInvalidRevisions()

    /** Emits [invalid] now and again whenever it changes. */
    public fun observeInvalid(): Flow<List<InvalidRevision>> =
        store.sync.observeShownInvalidRevisions()

    /**
     * Stops listing [text] in [invalid] on this device, as the user decided to discard it. Nothing
     * syncs: another device lists it until its user dismisses it there, as dismissing is about
     * what one person has looked at, and no revision can carry it without a format change.
     */
    public fun dismissInvalid(text: InvalidRevision) {
        store.sync.write { store.sync.dismissInvalid(text) }
    }

    /**
     * Saves [text] as a new note and stops listing it, in one transaction. Returns the note, or
     * null if [text] was saved or dismissed already: saving twice cannot make two notes.
     *
     * @throws NoteTooLargeException if it is over the cap, which only a damaged file can make.
     */
    public fun saveInvalidAsNote(text: InvalidRevision): Note? =
        store.sync.write {
            if (store.sync.isDismissed(text)) {
                null
            } else {
                store.notes.create(text.title, text.body).also { store.sync.dismissInvalid(text) }
            }
        }

    /** Returns the texts the user is combining by hand, kept on this device, by note. */
    public fun drafts(): List<ResolutionDraft> = store.sync.drafts()

    /** Emits [drafts] now and again whenever they change. */
    public fun observeDrafts(): Flow<List<ResolutionDraft>> = store.sync.observeDrafts()

    /**
     * Keeps [draft] on this device, in place of any draft for its target, until the answer is
     * written or [discardDraft]. It survives a restart, and outlives its target too: a conflict
     * resolved elsewhere leaves the draft for the user to save as a note or drop.
     */
    public fun putDraft(draft: ResolutionDraft) {
        store.sync.write { store.sync.putDraft(draft) }
    }

    /** Drops the draft for [targetId], if there is one. */
    public fun discardDraft(targetId: RevisionId) {
        store.sync.write { store.sync.deleteDraft(targetId) }
    }

    /** Runs [write] if [head] is [expectedHead], or else says which head the note shows now. */
    private fun answer(
        head: Revision,
        expectedHead: RevisionId,
        write: () -> Note?,
    ): ResolveResult =
        if (head.id == expectedHead) {
            ResolveResult.Resolved(write())
        } else {
            ResolveResult.ShownChanged(head.id)
        }

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
        store.sync.deleteDraft(closed)
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

/** The winning parent's and the losing parent's revisions, losing first. */
private fun RevisionGraph.parentsOf(
    mergeId: RevisionId,
    loserId: RevisionId,
): Pair<Revision, Revision> {
    val parents = checkNotNull(this[mergeId]).parents
    val loser = checkNotNull(this[loserId])
    return loser to checkNotNull(this[parents.first { it != loserId }])
}
