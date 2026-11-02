package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.backend.api.BackendUnavailableException
import io.github.sameerkrmishra.jotter.backend.api.BlobInfo
import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreException
import io.github.sameerkrmishra.jotter.core.model.DeviceId
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.Revision
import io.github.sameerkrmishra.jotter.core.model.RevisionContent
import io.github.sameerkrmishra.jotter.core.model.RevisionId
import io.github.sameerkrmishra.jotter.core.model.RevisionKind
import io.github.sameerkrmishra.jotter.core.storage.SyncStore

/**
 * How far above the last number of a folder a segment can be. The cycle records every number it
 * skips as a gap, so a file named `999999999999.json.gz` must not cost a trillion rows.
 */
internal const val MAX_SEGMENT_SKIP: Long = 4096

/** A segment that was read, or the reason why it is of no use. */
private class Fetched(
    val device: DeviceId,
    val number: Long,
    val segment: Segment?,
    val inflatedBytes: Long,
)

/**
 * Steps 2 and 3 of the sync cycle (ADR-0004): reads the segments past the cursors on one backend
 * and applies them. Each batch is one transaction, with its cursors, so a crash repeats a batch at
 * most. A batch ends at one segment's limit, for the sake of memory, and reading goes on.
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
        (gaps - files.keys).forEach { number ->
            val detail = "Still not on the backend"
            problems += SyncIssue.SegmentNotApplied(device, number, SegmentProblem.MISSING, detail)
        }
        var last = cursor
        for (number in files.keys.filter { it > cursor || it in gaps }.sorted()) {
            if (number - last > MAX_SEGMENT_SKIP) {
                problems += SyncIssue.ImplausibleSegmentNumber(device, number)
                return
            }
            batch += fetch(device, number, files.getValue(number))
            batchBytes += batch.last().inflatedBytes
            last = maxOf(last, number)
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
                Fetched(device, number, segment, segment.weight() + bytes.size)
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
            applySegments(fetched, touched, inserted)
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

    /**
     * Applies [fetched]. A merge is stored only with both parents here, so that it is judged the
     * same whatever came first: the others go in at once, and the merges follow as far as their
     * parents allow, also from a later segment of the batch. A segment with a merge left over
     * stays a gap, and the merge is tried again on the next cycle.
     */
    private fun applySegments(
        fetched: List<Fetched>,
        touched: MutableSet<NoteId>,
        inserted: MutableList<Revision>,
    ) {
        val accepted = fetched.filter { acceptSegment(it) }
        val waiting = mutableListOf<Pair<Fetched, Revision>>()
        accepted.forEach { item ->
            val (merges, others) =
                checkNotNull(item.segment).revisions.partition { it.kind != RevisionKind.User }
            others.forEach { store(item, it, touched, inserted) }
            merges.forEach { waiting += item to it }
        }
        val refused = HashSet<Fetched>()
        do {
            val ready =
                waiting.filter { (_, merge) ->
                    merge.parents.all {
                        store.revision(it) !=
                            null
                    }
                }
            ready.forEach { (item, merge) ->
                val problem = mergeProblem(merge, merge.parents.mapNotNull(store::revision))
                if (problem == null) {
                    store(item, merge, touched, inserted)
                } else {
                    report(item, SegmentProblem.REJECTED, problem)
                    refused += item
                }
            }
            waiting -= ready.toSet()
        } while (ready.isNotEmpty())
        waiting.forEach { (item, merge) ->
            report(item, SegmentProblem.WAITING, "Merge ${merge.id} waits for its parents")
        }
        val unfinished = refused + waiting.map { it.first }
        accepted.filter { it !in unfinished }.forEach { item ->
            store.removeGap(backendId, item.device, item.number)
            applied++
        }
        unfinished.forEach { store.addGap(backendId, it.device, it.number) }
    }

    /** Whether the segment of [item] can go in. If not, it becomes a gap and leaves no trace. */
    private fun acceptSegment(item: Fetched): Boolean {
        val rejection = item.segment?.let { rejectionOf(store, it) }
        if (rejection != null) report(item, SegmentProblem.REJECTED, rejection)
        val isAccepted = item.segment != null && rejection == null
        if (!isAccepted) store.addGap(backendId, item.device, item.number)
        return isAccepted
    }

    private fun report(
        item: Fetched,
        problem: SegmentProblem,
        detail: String,
    ) {
        problems += SyncIssue.SegmentNotApplied(item.device, item.number, problem, detail)
    }

    private fun store(
        item: Fetched,
        revision: Revision,
        touched: MutableSet<NoteId>,
        inserted: MutableList<Revision>,
    ) {
        if (store.revision(revision.id) == null) {
            store.insert(revision)
            inserted += revision
            newRevisions++
        }
        store.markOnBackend(backendId, revision.id, item.device, item.number)
        touched += revision.noteId
    }

    /** Moves each cursor past what was processed; numbers it skips become gaps. */
    private fun advanceCursors(fetched: List<Fetched>) {
        fetched.groupBy { it.device }.forEach { (device, segments) ->
            val cursor = store.cursor(backendId, device)
            val processed = segments.mapTo(HashSet()) { it.number }
            val last = processed.max()
            if (last > cursor) {
                for (number in (cursor + 1)..last) {
                    if (number !in processed) addHole(device, number)
                }
                store.setCursor(backendId, device, last)
            }
        }
    }

    private fun addHole(
        device: DeviceId,
        number: Long,
    ) {
        store.addGap(backendId, device, number)
        val detail = "Between segments that are there"
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

/**
 * Returns why [segment] contradicts the stored revisions, or null. It runs before anything is
 * written, so a rejected segment leaves no trace. A user revision can arrive before its parent,
 * so its rules are checked from both ends of an edge, against the parents known so far and the
 * children that came first. They also keep cycles out of the graph, as a child must not be stamped
 * below its parent. Merges wait for their parents, and [mergeProblem] judges them one by one.
 */
internal fun rejectionOf(
    store: SyncStore,
    segment: Segment,
): String? {
    val incoming = HashMap<RevisionId, Revision>()
    segment.revisions.forEach { revision ->
        val earlier = incoming.put(revision.id, revision)
        if (earlier != null && earlier != revision) return "Two revisions named ${revision.id}"
    }
    return incoming.values.firstNotNullOfOrNull { revision ->
        val stored = store.revision(revision.id)
        if (stored != null && stored != revision) {
            "Revision ${revision.id} is stored with other content"
        } else {
            val parents =
                revision.parents
                    .mapNotNull { incoming[it] ?: store.revision(it) }
                    .takeIf { revision.kind == RevisionKind.User }
                    .orEmpty()
            val children = store.childrenOf(revision.id).mapNotNull(store::revision)
            parents.firstNotNullOfOrNull { edgeProblem(it, revision) }
                ?: children.firstNotNullOfOrNull { edgeProblem(revision, it) }
        }
    }
}

private fun edgeProblem(
    parent: Revision,
    child: Revision,
): String? =
    when {
        parent.noteId != child.noteId -> {
            "Revision ${child.id} and its parent ${parent.id} are of different notes"
        }

        child.kind == RevisionKind.User && child.timestamp <= parent.timestamp -> {
            "User revision ${child.id} is not stamped above its parent ${parent.id}"
        }

        child.timestamp < parent.timestamp -> {
            "Merge ${child.id} is stamped below its parent ${parent.id}"
        }

        else -> {
            null
        }
    }

/** With both [parents] here, a merge must be of their note, with their stamp and loser. */
private fun mergeProblem(
    revision: Revision,
    parents: List<Revision>,
): String? {
    val merge = revision.kind as RevisionKind.AutomaticMerge
    val higher = parents.maxBy { it.stamp }
    val lower = parents.minBy { it.stamp }
    val isWellFormed =
        revision.timestamp == higher.timestamp &&
            revision.deviceId == higher.deviceId &&
            (merge.losingParent == null || merge.losingParent == lower.id)
    return parents.firstNotNullOfOrNull { edgeProblem(it, revision) }
        ?: "Merge ${revision.id} does not follow from its parents".takeUnless { isWellFormed }
}
