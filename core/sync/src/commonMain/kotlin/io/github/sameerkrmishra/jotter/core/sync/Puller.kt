package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.backend.api.BackendUnavailableException
import io.github.sameerkrmishra.jotter.backend.api.BlobInfo
import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreException
import io.github.sameerkrmishra.jotter.core.model.DeviceId
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.Revision
import io.github.sameerkrmishra.jotter.core.model.RevisionContent
import io.github.sameerkrmishra.jotter.core.storage.InvalidReason
import io.github.sameerkrmishra.jotter.core.storage.InvalidRevision
import io.github.sameerkrmishra.jotter.core.storage.SyncStore

/** A segment that was read, or the reason why it is of no use. */
private class Fetched(
    val device: DeviceId,
    val number: Long,
    val segment: Segment?,
    val inflatedBytes: Long,
    val size: Long = 0,
)

/**
 * Steps 2 and 3 of the sync cycle (ADR-0004): reads the segments past the cursors on one backend
 * and applies them. Each batch is one transaction, with its cursors, so a crash repeats a batch at
 * most. A batch ends at one segment's limit, for the sake of memory, and reading goes on.
 *
 * Whatever can be read is stored and passed on, also a revision that does not count: whether it
 * counts is for [RevisionGraph] to say, from the revisions alone. So every device ends up with the
 * same revisions, whatever order they came in, and a bad one takes nothing else with it.
 */
internal class Puller(
    private val store: SyncStore,
    private val backendId: String,
    private val blobs: BlobStore,
    private val seams: SyncSeams,
) {
    private val batch = mutableListOf<Fetched>()
    private var batchBytes = 0L
    private val problems = mutableListOf<SyncIssue>()

    /** The segments applied in this cycle. */
    var applied: Int = 0
        private set

    /** The revisions this device did not have. */
    var newRevisions: Int = 0
        private set

    /** The automatic merges made. */
    var merges: Int = 0
        private set

    /** What went wrong without stopping the cycle. */
    val issues: List<SyncIssue> get() = problems

    /**
     * Pulls [folders] and then settles the notes that have several heads, also if nothing new came.
     * A backend that goes away keeps what was read before: that is applied, and then the failure
     * is passed on.
     */
    suspend fun pull(folders: Map<DeviceId, Map<Long, BlobInfo>>) {
        try {
            folders.keys.sorted().forEach { device -> pullFolder(device, folders.getValue(device)) }
        } catch (unavailable: BackendUnavailableException) {
            applyBatch()
            throw unavailable
        }
        applyBatch()
    }

    private suspend fun pullFolder(
        device: DeviceId,
        files: Map<Long, BlobInfo>,
    ) {
        val cursor = store.cursor(backendId, device)
        val gaps = store.gaps(backendId, device)
        gaps.forEach { range ->
            val first = range.firstOrNull { it !in files }
            if (first != null) {
                val detail = "Numbers from here to ${range.last} are not on the backend"
                problems +=
                    SyncIssue.SegmentNotApplied(device, first, SegmentProblem.MISSING, detail)
            }
        }
        val todo = files.keys.filter { number -> number > cursor || gaps.any { number in it } }
        for (number in todo.sorted()) {
            batch += fetch(device, number, files.getValue(number))
            batchBytes += batch.last().inflatedBytes
            if (batchBytes >= seams.maxSegmentBytes) applyBatch()
        }
    }

    private suspend fun fetch(
        device: DeviceId,
        number: Long,
        file: BlobInfo,
    ): Fetched {
        val refused =
            fun(
                problem: SegmentProblem,
                detail: String,
            ): Fetched {
                problems += SyncIssue.SegmentNotApplied(device, number, problem, detail)
                return Fetched(device, number, null, 0)
            }
        if (file.size > seams.maxSegmentBytes) {
            return refused(SegmentProblem.CORRUPT, "The file has ${file.size} bytes")
        }
        return try {
            val bytes = blobs.read(file.path)
            val segment = seams.codec.decode(bytes)
            if (segment.device == device && segment.number == number) {
                Fetched(device, number, segment, segment.weight() + bytes.size, file.size)
            } else {
                val found = "segment ${segment.number} of ${segment.device}"
                refused(SegmentProblem.CORRUPT, "The file holds $found")
            }
        } catch (unavailable: BackendUnavailableException) {
            throw unavailable
        } catch (failure: BlobStoreException) {
            refused(SegmentProblem.UNREADABLE, failure.message.orEmpty())
        } catch (bad: SegmentFormatException) {
            val problem =
                if (bad.isFutureFormat) SegmentProblem.FUTURE_FORMAT else SegmentProblem.CORRUPT
            refused(problem, bad.message.orEmpty())
        }
    }

    /** Applies the batch in one transaction. Runs even when empty, to settle other notes. */
    private fun applyBatch() {
        val fetched = batch.toList()
        batch.clear()
        batchBytes = 0
        store.write {
            val touched = LinkedHashSet<NoteId>()
            val inserted = mutableListOf<Revision>()
            fetched.forEach { item ->
                val segment = item.segment
                if (segment == null) {
                    store.addGap(backendId, item.device, item.number..item.number)
                } else {
                    segment.dropped.forEach(store::addInvalid)
                    segment.revisions.forEach { store(item, it, touched, inserted) }
                    store.removeGap(backendId, item.device, item.number)
                    applied++
                }
            }
            advanceCursors(fetched)
            seams.probe.at(SyncPoint.REVISIONS_INSERTED)
            store.observe(inserted.map { it.timestamp }).forEach {
                problems += SyncIssue.ClockDrift(it.remote)
            }
            (touched + store.notesWithSeveralHeads()).forEach { noteId ->
                merges += reconcile(store, noteId).size
            }
        }
    }

    private fun store(
        item: Fetched,
        revision: Revision,
        touched: MutableSet<NoteId>,
        inserted: MutableList<Revision>,
    ) {
        val stored = store.revision(revision.id)
        when {
            stored == null -> {
                store.insert(revision)
                inserted += revision
                newRevisions++
                // A revision that came first may have been waiting to learn where this one is.
                store.childrenOf(revision.id).mapNotNullTo(touched) { store.revision(it)?.noteId }
            }

            stored != revision -> {
                settleIdCollision(stored, revision)
                touched += stored.noteId
            }
        }
        store.markOnBackend(backendId, revision.id, item.device, item.number, item.size)
        touched += revision.noteId
    }

    /**
     * Two revisions under one id: a bug, as ids are random. Each device that sees both keeps the
     * same one, the first in the order of their JSON, and lists the other, so what stays does not
     * depend on which came first. The winner counts as on no backend, so it is passed on.
     */
    private fun settleIdCollision(
        stored: Revision,
        incoming: Revision,
    ) {
        val incomingWins = revisionJson(incoming) < revisionJson(stored)
        val loser = if (incomingWins) stored else incoming
        val text = loser.content as? RevisionContent.Snapshot
        store.addInvalid(
            InvalidRevision(
                loser.id,
                loser.noteId,
                InvalidReason.ID_COLLISION,
                text?.title.orEmpty(),
                text?.body.orEmpty(),
            ),
        )
        if (incomingWins) store.replace(incoming)
    }

    /** Moves each cursor past what was processed; a run of numbers it skips is one gap. */
    private fun advanceCursors(fetched: List<Fetched>) {
        fetched.groupBy { it.device }.forEach { (device, segments) ->
            var last = store.cursor(backendId, device)
            segments.map { it.number }.sorted().filter { it > last }.forEach { number ->
                if (number > last + 1) addHole(device, (last + 1)..(number - 1))
                last = number
            }
            store.setCursor(backendId, device, last)
        }
    }

    private fun addHole(
        device: DeviceId,
        range: LongRange,
    ) {
        store.addGap(backendId, device, range)
        val detail = "Numbers up to ${range.last}, between segments that are there"
        val number = range.first
        problems += SyncIssue.SegmentNotApplied(device, number, SegmentProblem.MISSING, detail)
    }
}

/** Roughly what [Segment] takes in memory, however well its file was compressed. */
private fun Segment.weight(): Long =
    revisions.sumOf { revision ->
        val content = revision.content as? RevisionContent.Snapshot
        REVISION_WEIGHT + 2L * (content?.let { it.title.length + it.body.length } ?: 0)
    }

private const val REVISION_WEIGHT = 1024L
