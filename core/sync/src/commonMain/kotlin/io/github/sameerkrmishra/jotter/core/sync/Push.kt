package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.backend.api.BlobAlreadyExistsException
import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.core.model.DeviceId
import io.github.sameerkrmishra.jotter.core.model.Revision
import io.github.sameerkrmishra.jotter.core.model.RevisionContent
import io.github.sameerkrmishra.jotter.core.storage.SyncStore

/** The places where a test can stop a cycle, as a crash would. Does nothing in production. */
internal enum class SyncPoint {
    SEGMENT_RECORDED,
    SEGMENT_WRITTEN,
    REVISIONS_INSERTED,
}

/** Called at each [SyncPoint]. */
internal fun interface SyncProbe {
    fun at(point: SyncPoint)
}

/**
 * What a test can replace in the cycle.
 *
 * @property codec Turns segments into files.
 * @property probe Called at each [SyncPoint].
 * @property maxSegmentBytes The limit on a segment, and on what one pull holds in memory.
 */
internal class SyncSeams(
    val codec: SegmentCodec = JsonGzipCodec(),
    val probe: SyncProbe = SyncProbe {},
    val maxSegmentBytes: Long = MAX_SEGMENT_BYTES,
)

/**
 * Splits revisions into segments that are sure to stay within [maxBytes] as JSON, without encoding
 * them to find out: a UTF-16 unit takes at most [JSON_BYTES_PER_CHAR] bytes as JSON.
 */
internal class SegmentBatches(
    private val maxBytes: Long,
) {
    /** The revisions of the batches, and those that fit in none. */
    fun split(revisions: List<Revision>): Pair<List<List<Revision>>, List<Revision>> {
        val (fitting, tooLarge) = revisions.partition { cost(it) <= maxBytes }
        val batches = mutableListOf<List<Revision>>()
        val batch = mutableListOf<Revision>()
        var bytes = 0L
        fitting.forEach { revision ->
            if (bytes + cost(revision) > maxBytes) {
                batches += batch.toList()
                batch.clear()
                bytes = 0
            }
            batch += revision
            bytes += cost(revision)
        }
        if (batch.isNotEmpty()) batches += batch
        return batches to tooLarge
    }

    private fun cost(revision: Revision): Long {
        val content = revision.content as? RevisionContent.Snapshot
        val chars =
            content?.let { snapshot ->
                snapshot.title.length.toLong() +
                    snapshot.body.length +
                    snapshot.tags.sumOf { it.toString().length + TAG_OVERHEAD }
            } ?: 0
        return REVISION_OVERHEAD + chars * JSON_BYTES_PER_CHAR
    }

    private companion object {
        const val JSON_BYTES_PER_CHAR = 6L
        const val TAG_OVERHEAD = 4
        const val REVISION_OVERHEAD = 2048L
    }
}

/**
 * Step 1 of the sync cycle (ADR-0004): publishes this device's segments on one backend. A segment
 * is recorded with its number and revisions before it is written, and from then on it is never
 * changed or given up: every failure of `writeNew`, a retry's too, may have published the file, so
 * only a success or the same content at the path marks it as published.
 */
internal class Pusher(
    private val store: SyncStore,
    private val backendId: String,
    private val blobs: BlobStore,
    private val device: DeviceId,
    private val seams: SyncSeams,
) {
    private val batches = SegmentBatches(seams.maxSegmentBytes)
    private val issues = mutableListOf<SyncIssue>()

    /** The segments published in this cycle. */
    var pushed: Int = 0
        private set

    /** What went wrong without ending the cycle. */
    val problems: List<SyncIssue> get() = issues

    /**
     * Publishes what is unfinished and then what is new. [listed] are the numbers of this device's
     * segments on the backend: a published segment missing from it was lost, for example to a power
     * cut on the backend (ADR-0012), and is written again, under its number and with its content.
     */
    suspend fun push(listed: Set<Long>) {
        val unfinished =
            store.ownSegments(backendId).filter { (number, isPublished) ->
                !isPublished || number !in listed
            }
        for (number in unfinished.keys.sorted()) {
            if (!publish(number)) return
        }
        val (segments, tooLarge) = batches.split(unpushedRevisions())
        tooLarge.forEach { issues += SyncIssue.RevisionTooLarge(it.id) }
        val highestListed = listed.maxOrNull() ?: 0
        for (revisions in segments) {
            if (!publish(record(revisions, highestListed))) return
        }
    }

    private fun unpushedRevisions(): List<Revision> =
        store.unpushed(backendId).map { id -> checkNotNull(store.revision(id)) { "$id is gone" } }

    /**
     * Numbers the new segment above both what this device ever used and what the folder holds. A
     * backend that was forgotten and added again keeps its files (PLAN.md §6.5), and those numbers
     * are taken. A number is never used twice, even if its file is gone: a reader may be past it.
     */
    private fun record(
        revisions: List<Revision>,
        highestListed: Long,
    ): Long =
        store.write {
            val number = maxOf(store.lastSegmentNumber(backendId), highestListed) + 1
            store.recordSegment(backendId, number, revisions.map { it.id })
            number
        }

    /** Returns false after a collision, which stops pushing to this backend. */
    private suspend fun publish(number: Long): Boolean {
        seams.probe.at(SyncPoint.SEGMENT_RECORDED)
        val segment = Segment(device, number, store.revisionsOfSegment(backendId, number))
        val path = BackendLayout.segmentPath(device, number)
        val isOurs =
            try {
                blobs.writeNew(path, seams.codec.encode(segment))
                true
            } catch (_: BlobAlreadyExistsException) {
                holdsSameContent(path, segment)
            }
        if (!isOurs) {
            issues += SyncIssue.SegmentCollision(number)
            return false
        }
        seams.probe.at(SyncPoint.SEGMENT_WRITTEN)
        store.write { store.markSegmentPublished(backendId, number) }
        pushed++
        return true
    }

    /** ADR-0004 compares the decoded content, as gzip bytes need not be the same. */
    private suspend fun holdsSameContent(
        path: String,
        segment: Segment,
    ): Boolean =
        try {
            val found = seams.codec.decode(blobs.read(path))
            found.device == segment.device &&
                found.number == segment.number &&
                found.revisions.toSet() == segment.revisions.toSet()
        } catch (_: SegmentFormatException) {
            false
        }
}
