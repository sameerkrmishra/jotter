package io.github.sameerkrmishra.jotter.core.storage

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.github.sameerkrmishra.jotter.core.model.ClockDriftException
import io.github.sameerkrmishra.jotter.core.model.DeviceId
import io.github.sameerkrmishra.jotter.core.model.HybridLogicalClock
import io.github.sameerkrmishra.jotter.core.model.HybridTimestamp
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.Revision
import io.github.sameerkrmishra.jotter.core.model.RevisionContent
import io.github.sameerkrmishra.jotter.core.model.RevisionId
import io.github.sameerkrmishra.jotter.core.model.RevisionKind
import io.github.sameerkrmishra.jotter.core.storage.db.JotterDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

/**
 * An open conflict (ADR-0003): a conflicted merge that nothing closed.
 *
 * @property mergeId The conflicted merge.
 * @property noteId Its note.
 * @property losingRevisionId The merge's parent whose text lost.
 * @property losingTitle The title that lost.
 * @property losingBody The body that lost.
 */
public data class OpenConflict(
    val mergeId: RevisionId,
    val noteId: NoteId,
    val losingRevisionId: RevisionId,
    val losingTitle: String,
    val losingBody: String,
)

/** Why a [HiddenHead] waits. */
public enum class HiddenReason {
    /** An ancestor has not arrived, and may never. The user can settle the head. */
    MISSING_ANCESTOR,

    /** An open conflict holds merging back. The head merges once that is resolved. */
    OPEN_CONFLICT,
}

/**
 * A head with text that its note does not show until a merge can run (ADR-0014).
 *
 * @property revisionId The head.
 * @property noteId Its note.
 * @property title Its title.
 * @property body Its body.
 * @property reason Why it waits.
 */
public data class HiddenHead(
    val revisionId: RevisionId,
    val noteId: NoteId,
    val title: String,
    val body: String,
    val reason: HiddenReason,
)

/**
 * A segment this device wrote on a backend.
 *
 * @property number Its number.
 * @property name Its file name.
 * @property isPublished Whether it is known to be on the backend.
 */
public data class OwnSegment(
    val number: Long,
    val name: String,
    val isPublished: Boolean,
)

/** Why text does not count (ADR-0014). */
public enum class InvalidReason {
    /** The revision, or one it builds on, does not fit its parents, or has a parent elsewhere. */
    BAD_HISTORY,

    /** The revision could not be read, so only what could be read of it is here. */
    MALFORMED,

    /** Two different revisions were seen under this id, so neither counts. */
    ID_COLLISION,
}

/**
 * Text that the DAG does not count, listed so that it is not lost in silence (ADR-0014).
 *
 * @property revisionId The revision.
 * @property noteId Its note.
 * @property reason Why it does not count.
 * @property title Its title.
 * @property body Its body.
 */
public data class InvalidRevision(
    val revisionId: RevisionId,
    val noteId: NoteId,
    val reason: InvalidReason,
    val title: String,
    val body: String,
)

/**
 * Text the user is combining by hand to answer an open conflict or a hidden head, kept on this
 * device only so that it outlives the app.
 *
 * @property targetId The conflicted merge, or the hidden head, that the text answers.
 * @property noteId Its note.
 * @property isConflict Whether [targetId] is a conflicted merge rather than a hidden head.
 * @property baseId The head whose text the user combined against.
 * @property title The title as typed.
 * @property body The body as typed.
 */
public data class ResolutionDraft(
    val targetId: RevisionId,
    val noteId: NoteId,
    val isConflict: Boolean,
    val baseId: RevisionId,
    val title: String,
    val body: String,
)

/**
 * Marks what is for `core:sync` alone. Kotlin has no friend modules, so [SyncStore] cannot be
 * `internal` to `core:storage` and still reach `core:sync`. No app needs it: this opt-in makes any
 * other use a compile error.
 */
@RequiresOptIn("Only core:sync may use SyncStore", RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
public annotation class SyncStoreApi

/**
 * The tables behind sync, for `core:sync`, which owns every rule about them: this class stores
 * and reads, and decides nothing. See PLAN.md §6 and ADR-0004.
 *
 * Functions that write belong in one [write]. All of them block on database I/O.
 */
@SyncStoreApi
@Suppress("TooManyFunctions") // One small function per query, kept together on purpose.
public class SyncStore internal constructor(
    private val database: JotterDatabase,
    private val deviceId: DeviceId,
    private val clock: Clock,
    private val queryContext: CoroutineContext,
    private val ids: IdSource,
) {
    private val queries = database.syncQueries
    private val revisions = RevisionStore(database.revisionQueries)
    private val materialiser = NoteMaterialiser(database.noteQueries)

    /**
     * Runs [block] in one write transaction, which any exception rolls back. [block] must not wait
     * for another thread that uses the database.
     *
     * @throws StorageBusyException if another process kept the database locked.
     */
    public fun <T> write(block: () -> T): T =
        reportingBusy { database.transactionWithResult { block() } }

    /** Returns the revision with [id], or null if it is not stored. */
    public fun revision(id: RevisionId): Revision? = revisions.get(id)

    /** Returns every stored revision of the note [noteId]. */
    public fun revisions(noteId: NoteId): List<Revision> = revisions.forNote(noteId)

    /** Returns the ids of the stored revisions built directly on [id]. */
    public fun childrenOf(id: RevisionId): List<RevisionId> = revisions.childrenOf(id)

    /** Returns the notes with more than one head. */
    public fun notesWithSeveralHeads(): List<NoteId> = revisions.notesWithSeveralHeads()

    /**
     * Stores [revision], idempotently by id.
     *
     * @throws IllegalArgumentException if another revision is stored under its id, or its text
     *   cannot be stored unchanged.
     */
    public fun insert(revision: Revision) {
        revisions.insert(revision)
    }

    /** Makes the note of [head] show it; a tombstone removes the note. [head] must be stored. */
    public fun materialise(head: Revision) {
        materialiser.materialise(head)
    }

    /**
     * Stores a user revision on top of [parent] with the device's next timestamp. The
     * last-writer-wins fields of [content] keep the stamps they have: a resolution writes text.
     *
     * @param resolves The conflicted merge this closes.
     */
    public fun insertResolution(
        parent: Revision,
        content: RevisionContent.Snapshot,
        resolves: RevisionId,
    ): Revision =
        withClock { hlc ->
            val revision =
                Revision(
                    id = ids.newRevisionId(),
                    noteId = parent.noteId,
                    parents = setOf(parent.id),
                    timestamp = hlc.now(after = parent.timestamp),
                    deviceId = deviceId,
                    kind = RevisionKind.User,
                    content = content,
                    resolves = resolves,
                )
            revisions.insert(revision)
            revision
        }

    /**
     * Moves the device's clock past [timestamps] and returns the rejections for those too far ahead
     * of the wall clock, which it leaves out (ADR-0003, refinements).
     */
    public fun observe(timestamps: List<HybridTimestamp>): List<ClockDriftException> =
        withClock { hlc ->
            timestamps.mapNotNull { timestamp ->
                try {
                    hlc.observe(timestamp)
                    null
                } catch (drift: ClockDriftException) {
                    drift
                }
            }
        }

    /** Returns this device's segments on [backend], by number. */
    public fun ownSegments(backend: String): List<OwnSegment> =
        queries.selectOwnSegments(backend, ::OwnSegment).executeAsList()

    /** Records a new segment of this device on [backend], with [revisionIds], as unpublished. */
    public fun recordSegment(
        backend: String,
        number: Long,
        name: String,
        revisionIds: List<RevisionId>,
    ) {
        queries.insertOwnSegment(backend, number, name)
        revisionIds.forEach { queries.insertPushedRevision(backend, it.toString(), number) }
    }

    /** Records that segment [number] of this device is on [backend]. */
    public fun markSegmentPublished(
        backend: String,
        number: Long,
    ) {
        queries.markSegmentPublished(backend, number)
    }

    /**
     * Gives up this device's segment [number] on [backend], whose file is damaged. Its revisions
     * count as not on the backend again, so the next push writes them to a new file.
     */
    public fun abandonSegment(
        backend: String,
        number: Long,
    ) {
        write {
            queries.deleteRevisionsOfSegment(backend, number)
            queries.deleteOwnSegment(backend, number)
        }
    }

    /** Returns the revisions recorded for this device's segment [number], in arrival order. */
    public fun revisionsOfSegment(
        backend: String,
        number: Long,
    ): List<Revision> =
        queries
            .selectRevisionsOfSegment(backend, number)
            .executeAsList()
            .map { id -> checkNotNull(revisions.get(RevisionId.parse(id))) { "$id is gone" } }

    /** Returns the ids of the revisions not known to be on [backend], in arrival order. */
    public fun unpushed(backend: String): List<RevisionId> =
        queries.selectUnpushed(backend).executeAsList().map(RevisionId::parse)

    /** Removes the note [noteId] from what the UI reads, as no revision of it counts. */
    public fun removeNote(noteId: NoteId) {
        materialiser.remove(noteId.toString())
    }

    /** Replaces the [InvalidReason.BAD_HISTORY] rows of the note [noteId] with [rows]. */
    public fun replaceBadHistory(
        noteId: NoteId,
        rows: List<InvalidRevision>,
    ) {
        queries.deleteBadHistoryOfNote(noteId.toString())
        rows.forEach(::addInvalid)
    }

    /** Records [row], once. */
    public fun addInvalid(row: InvalidRevision) {
        queries.insertInvalidRevision(
            row.revisionId.toString(),
            row.noteId.toString(),
            row.reason.name,
            row.title,
            row.body,
        )
    }

    /** Returns the text that the DAG does not count, by note. */
    public fun invalidRevisions(): List<InvalidRevision> =
        queries.selectInvalidRevisions(::invalidOfRow).executeAsList()

    /** Emits [invalidRevisions] now and again whenever they change. */
    public fun observeInvalidRevisions(): Flow<List<InvalidRevision>> =
        queries
            .selectInvalidRevisions(::invalidOfRow)
            .asFlow()
            .mapToList(queryContext)
            .distinctUntilChanged()

    /** Returns the ids that two different revisions were seen under. */
    public fun collidedIds(): Set<RevisionId> =
        queries
            .selectCollidedIds()
            .executeAsList()
            .mapTo(HashSet(), RevisionId::parse)

    /** Records that the stored revision [id] was found on [backend], in the file [name]. */
    public fun markOnBackend(
        backend: String,
        id: RevisionId,
        device: DeviceId,
        name: String,
    ) {
        queries.insertPulledRevision(backend, id.toString(), device.toString(), name)
    }

    /** Records that the file [name] in the folder of [device] on [backend] has been read. */
    public fun recordRead(
        backend: String,
        device: DeviceId,
        name: String,
    ) {
        queries.insertRead(backend, device.toString(), name)
    }

    /** Returns the files read on [backend]: the folder and the file name. */
    public fun readFiles(backend: String): List<Pair<DeviceId, String>> =
        queries
            .selectReads(backend) { device, name -> DeviceId.parse(device) to name }
            .executeAsList()

    /** Forgets the file [name] of [device] on [backend], and that its revisions are there. */
    public fun forgetFile(
        backend: String,
        device: DeviceId,
        name: String,
    ) {
        queries.deleteRead(backend, device.toString(), name)
        queries.deleteRevisionsReadFrom(backend, device.toString(), name)
    }

    /** Forgets [backend] (PLAN.md §6.5), in one transaction. */
    public fun forgetBackend(backend: String) {
        write {
            queries.deleteBackendReads(backend)
            queries.deleteBackendSegments(backend)
            queries.deleteBackendRevisions(backend)
        }
    }

    /** Replaces the open conflicts of the note [noteId] with [conflicts]. */
    public fun replaceConflicts(
        noteId: NoteId,
        conflicts: List<OpenConflict>,
    ) {
        queries.deleteConflictsOfNote(noteId.toString())
        conflicts.forEach { conflict ->
            queries.insertConflict(
                conflict.mergeId.toString(),
                conflict.noteId.toString(),
                conflict.losingRevisionId.toString(),
                conflict.losingTitle,
                conflict.losingBody,
            )
        }
    }

    /** Replaces the hidden heads of the note [noteId] with [heads]. */
    public fun replaceHiddenHeads(
        noteId: NoteId,
        heads: List<HiddenHead>,
    ) {
        queries.deleteHiddenHeadsOfNote(noteId.toString())
        heads.forEach { head ->
            queries.insertHiddenHead(
                head.revisionId.toString(),
                head.noteId.toString(),
                head.title,
                head.body,
                head.reason == HiddenReason.MISSING_ANCESTOR,
            )
        }
    }

    /** Returns the text that the DAG does not count, by note, but what this device dismissed. */
    public fun shownInvalidRevisions(): List<InvalidRevision> =
        queries.selectShownInvalidRevisions(::invalidOfRow).executeAsList()

    /** Emits [shownInvalidRevisions] now and again whenever they change. */
    public fun observeShownInvalidRevisions(): Flow<List<InvalidRevision>> =
        queries
            .selectShownInvalidRevisions(::invalidOfRow)
            .asFlow()
            .mapToList(queryContext)
            .distinctUntilChanged()

    /** Records that the user dismissed [row] on this device. */
    public fun dismissInvalid(row: InvalidRevision) {
        queries.insertDismissedInvalid(
            row.revisionId.toString(),
            row.reason.name,
            row.title,
            row.body,
        )
    }

    /** Returns whether the user dismissed [row] on this device. */
    public fun isDismissed(row: InvalidRevision): Boolean =
        queries
            .countDismissedInvalid(
                row.revisionId.toString(),
                row.reason.name,
                row.title,
                row.body,
            ).executeAsOne() > 0

    /** Stores [draft] in place of any draft for the same target. */
    public fun putDraft(draft: ResolutionDraft) {
        queries.upsertDraft(
            draft.targetId.toString(),
            draft.noteId.toString(),
            draft.isConflict,
            draft.baseId.toString(),
            draft.title,
            draft.body,
        )
    }

    /** Removes the draft for [targetId], if there is one. */
    public fun deleteDraft(targetId: RevisionId) {
        queries.deleteDraft(targetId.toString())
    }

    /** Returns every draft, by note. */
    public fun drafts(): List<ResolutionDraft> = queries.selectDrafts(::draftOfRow).executeAsList()

    /** Emits [drafts] now and again whenever they change. */
    public fun observeDrafts(): Flow<List<ResolutionDraft>> =
        queries
            .selectDrafts(::draftOfRow)
            .asFlow()
            .mapToList(queryContext)
            .distinctUntilChanged()

    /** Returns every hidden head, by note. */
    public fun hiddenHeads(): List<HiddenHead> =
        queries.selectHiddenHeads(::hiddenHeadOfRow).executeAsList()

    /** Emits [hiddenHeads] now and again whenever they change. */
    public fun observeHiddenHeads(): Flow<List<HiddenHead>> =
        queries
            .selectHiddenHeads(::hiddenHeadOfRow)
            .asFlow()
            .mapToList(queryContext)
            .distinctUntilChanged()

    /** Returns every open conflict, by note. */
    public fun conflicts(): List<OpenConflict> =
        queries.selectConflicts(::conflictOfRow).executeAsList()

    /** Returns the open conflict of the merge [mergeId], or null if there is none. */
    public fun conflict(mergeId: RevisionId): OpenConflict? =
        queries.selectConflict(mergeId.toString(), ::conflictOfRow).executeAsOneOrNull()

    /** Emits [conflicts] now and again whenever they change. */
    public fun observeConflicts(): Flow<List<OpenConflict>> =
        queries
            .selectConflicts(::conflictOfRow)
            .asFlow()
            .mapToList(queryContext)
            .distinctUntilChanged()

    /** Runs [block] with the device's clock and stores the clock's new time. */
    private fun <T> withClock(block: (HybridLogicalClock) -> T): T =
        write {
            val stored = database.deviceQueries.selectDevice().executeAsOne()
            val last = hybridTimestamp(stored.hlc_physical, stored.hlc_counter)
            val hlc = HybridLogicalClock(clock, last)
            val result = block(hlc)
            if (hlc.last != last) {
                database.deviceQueries.updateClock(
                    hlc.last.physicalMillis,
                    hlc.last.counter.toLong(),
                )
            }
            result
        }
}

private fun invalidOfRow(
    revisionId: String,
    noteId: String,
    reason: String,
    title: String,
    body: String,
): InvalidRevision =
    InvalidRevision(
        RevisionId.parse(revisionId),
        NoteId.parse(noteId),
        InvalidReason.valueOf(reason),
        title,
        body,
    )

private fun hiddenHeadOfRow(
    revisionId: String,
    noteId: String,
    title: String,
    body: String,
    isIncomplete: Boolean,
): HiddenHead =
    HiddenHead(
        revisionId = RevisionId.parse(revisionId),
        noteId = NoteId.parse(noteId),
        title = title,
        body = body,
        reason = if (isIncomplete) HiddenReason.MISSING_ANCESTOR else HiddenReason.OPEN_CONFLICT,
    )

private fun conflictOfRow(
    mergeId: String,
    noteId: String,
    losingRevisionId: String,
    losingTitle: String,
    losingBody: String,
): OpenConflict =
    OpenConflict(
        mergeId = RevisionId.parse(mergeId),
        noteId = NoteId.parse(noteId),
        losingRevisionId = RevisionId.parse(losingRevisionId),
        losingTitle = losingTitle,
        losingBody = losingBody,
    )

@Suppress("LongParameterList") // One parameter per column, as SQLDelight passes them.
private fun draftOfRow(
    targetId: String,
    noteId: String,
    isConflict: Boolean,
    baseId: String,
    title: String,
    body: String,
): ResolutionDraft =
    ResolutionDraft(
        targetId = RevisionId.parse(targetId),
        noteId = NoteId.parse(noteId),
        isConflict = isConflict,
        baseId = RevisionId.parse(baseId),
        title = title,
        body = body,
    )
