package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.backend.api.BackendUnavailableException
import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.api.BlobStoreException
import io.github.sameerkrmishra.jotter.core.model.DeviceId
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.Revision
import io.github.sameerkrmishra.jotter.core.model.RevisionContent
import io.github.sameerkrmishra.jotter.core.storage.InvalidReason
import io.github.sameerkrmishra.jotter.core.storage.InvalidRevision
import io.github.sameerkrmishra.jotter.core.storage.SyncStore

/** A segment that was read. */
private class Fetched(
    val device: DeviceId,
    val file: SegmentFile,
    val segment: Segment,
    val inflatedBytes: Long,
)

/**
 * Steps 2 and 3 of the sync cycle (ADR-0004): reads the files that have not been read yet, in every
 * folder on one backend, this device's too, and applies them. A file that cannot be used stays
 * unread, is tried again on the next cycle, and blocks nothing. A device restored from a backup
 * gets what it lost from its own folder in this way, like any other file.
 *
 * Each batch is one transaction, with its records of what was read, so a crash repeats a batch at
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
    suspend fun pull(folders: Map<DeviceId, List<SegmentFile>>) {
        val read = store.readFiles(backendId).toSet()
        try {
            folders.keys.sorted().forEach { device ->
                val files = folders.getValue(device)
                reportHoles(device, files)
                files.filter { (device to it.name) !in read }.forEach { fetch(device, it) }
            }
        } catch (unavailable: BackendUnavailableException) {
            applyBatch()
            throw unavailable
        }
        applyBatch()
    }

    private fun reportHoles(
        device: DeviceId,
        files: List<SegmentFile>,
    ) {
        var last = 0L
        files.map { it.number }.distinct().forEach { number ->
            if (number > last + 1) {
                val detail = "Numbers up to ${number - 1} are not on the backend"
                val missing = SegmentProblem.MISSING
                problems += SyncIssue.SegmentNotApplied(device, last + 1, missing, detail)
            }
            last = number
        }
    }

    private suspend fun fetch(
        device: DeviceId,
        file: SegmentFile,
    ) {
        val refused =
            fun(
                problem: SegmentProblem,
                detail: String,
            ) {
                problems += SyncIssue.SegmentNotApplied(device, file.number, problem, detail)
            }
        if (file.info.size > seams.maxSegmentBytes) {
            refused(SegmentProblem.CORRUPT, "The file has ${file.info.size} bytes")
            return
        }
        try {
            val bytes = blobs.read(file.info.path)
            val segment = seams.codec.decode(bytes, file.hash)
            if (segment.device == device && segment.number == file.number) {
                batch += Fetched(device, file, segment, segment.weight() + bytes.size)
                batchBytes += batch.last().inflatedBytes
                if (batchBytes >= seams.maxSegmentBytes) applyBatch()
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
                item.segment.dropped.forEach(store::addInvalid)
                item.segment.revisions.forEach { store(item, it, touched, inserted) }
                store.recordRead(backendId, item.device, item.file.name)
                applied++
            }
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
                listCollision(stored)
                listCollision(revision)
                touched += stored.noteId
            }
        }
        store.markOnBackend(backendId, revision.id, item.device, item.file.name)
        touched += revision.noteId
    }

    /**
     * Two revisions under one id: a bug, as ids are random. Neither counts, on any device that has
     * seen both, and the text of each is listed.
     */
    private fun listCollision(copy: Revision) {
        val text = copy.content as? RevisionContent.Snapshot
        store.addInvalid(
            InvalidRevision(
                copy.id,
                copy.noteId,
                InvalidReason.ID_COLLISION,
                text?.title.orEmpty(),
                text?.body.orEmpty(),
            ),
        )
    }
}

/** Roughly what [Segment] takes in memory, however well its file was compressed. */
private fun Segment.weight(): Long =
    revisions.sumOf { revision ->
        val content = revision.content as? RevisionContent.Snapshot
        REVISION_WEIGHT + 2L * (content?.let { it.title.length + it.body.length } ?: 0)
    }

private const val REVISION_WEIGHT = 1024L
