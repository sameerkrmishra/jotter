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

/**
 * The tables behind sync, for `core:sync`, which owns every rule about them: this class stores
 * and reads, and decides nothing. See PLAN.md §6 and ADR-0004.
 *
 * Functions that write belong in one [write]. All of them block on database I/O.
 */
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

    /** Returns the highest segment number processed from [device] on [backend], or 0. */
    public fun cursor(
        backend: String,
        device: DeviceId,
    ): Long = queries.selectCursor(backend, device.toString()).executeAsOneOrNull() ?: 0

    /** Sets the highest segment number processed from [device] on [backend]. */
    public fun setCursor(
        backend: String,
        device: DeviceId,
        segment: Long,
    ) {
        queries.upsertCursor(backend, device.toString(), segment)
    }

    /** Returns the numbers at or below the cursor of [device] on [backend] not applied yet. */
    public fun gaps(
        backend: String,
        device: DeviceId,
    ): Set<Long> = queries.selectGaps(backend, device.toString()).executeAsList().toSet()

    /** Records that segment [number] of [device] on [backend] was not applied. */
    public fun addGap(
        backend: String,
        device: DeviceId,
        number: Long,
    ) {
        queries.insertGap(backend, device.toString(), number)
    }

    /** Records that segment [number] of [device] on [backend] has been applied. */
    public fun removeGap(
        backend: String,
        device: DeviceId,
        number: Long,
    ) {
        queries.deleteGap(backend, device.toString(), number)
    }

    /** Returns this device's segments on [backend], by number, and whether each is published. */
    public fun ownSegments(backend: String): Map<Long, Boolean> =
        queries
            .selectOwnSegments(backend)
            .executeAsList()
            .associate { it.number to it.is_published }

    /** Records a new segment of this device on [backend], with [revisionIds], as unpublished. */
    public fun recordSegment(
        backend: String,
        number: Long,
        revisionIds: List<RevisionId>,
    ) {
        queries.insertOwnSegment(backend, number)
        revisionIds.forEach { queries.insertPushedRevision(backend, it.toString(), number) }
    }

    /** Records that segment [number] of this device is on [backend]. */
    public fun markSegmentPublished(
        backend: String,
        number: Long,
    ) {
        queries.markSegmentPublished(backend, number)
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

    /** Records that the stored revision [id] was found on [backend]. */
    public fun markOnBackend(
        backend: String,
        id: RevisionId,
    ) {
        queries.insertPulledRevision(backend, id.toString())
    }

    /** Forgets everything about [backend] (PLAN.md §6.5), in one transaction. */
    public fun forgetBackend(backend: String) {
        write {
            queries.deleteBackendCursors(backend)
            queries.deleteBackendGaps(backend)
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
