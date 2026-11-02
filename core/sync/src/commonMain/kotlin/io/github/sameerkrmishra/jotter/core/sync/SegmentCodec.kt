package io.github.sameerkrmishra.jotter.core.sync

import io.github.sameerkrmishra.jotter.core.model.DeviceId
import io.github.sameerkrmishra.jotter.core.model.HybridTimestamp
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.Revision
import io.github.sameerkrmishra.jotter.core.model.RevisionContent
import io.github.sameerkrmishra.jotter.core.model.RevisionId
import io.github.sameerkrmishra.jotter.core.model.RevisionKind
import io.github.sameerkrmishra.jotter.core.model.Stamp
import io.github.sameerkrmishra.jotter.core.model.Stamped
import io.github.sameerkrmishra.jotter.core.model.TagName
import io.github.sameerkrmishra.jotter.core.storage.InvalidReason
import io.github.sameerkrmishra.jotter.core.storage.InvalidRevision
import io.github.sameerkrmishra.jotter.core.storage.isStorableText
import io.github.sameerkrmishra.jotter.core.storage.toStorableText
import io.github.sameerkrmishra.jotter.core.sync.dto.RevisionDto
import io.github.sameerkrmishra.jotter.core.sync.dto.SegmentDto
import io.github.sameerkrmishra.jotter.core.sync.dto.SegmentHeaderDto
import io.github.sameerkrmishra.jotter.core.sync.dto.SnapshotDto
import io.github.sameerkrmishra.jotter.core.sync.dto.StampDto
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.GzipSink
import okio.GzipSource
import okio.IOException
import okio.buffer
import okio.use

/** The format this code reads and writes, in `format.json` and in every segment. */
internal const val SYNC_FORMAT = 1

/** The codec this code uses, in `format.json` (PLAN.md §6.6). */
internal const val SYNC_CODEC = "json+gzip"

/**
 * The largest segment read, compressed or inflated: the limit on what one file can cost. A push
 * never writes more, see [SegmentBatches]. 32 MiB holds years of notes of 10 to 100 lines.
 */
internal const val MAX_SEGMENT_BYTES: Long = 32L * 1024 * 1024

/**
 * An immutable batch of revisions from one device.
 *
 * @property device The device whose folder holds it.
 * @property number Its number in that folder.
 * @property revisions The revisions, in the order they arrived on [device].
 * @property dropped User revisions that could not be read, with what could be read of them.
 */
internal data class Segment(
    val device: DeviceId,
    val number: Long,
    val revisions: List<Revision>,
    val dropped: List<InvalidRevision> = emptyList(),
)

/** Turns segments into the bytes of a file and back: the seam for encryption (PLAN.md §6.6). */
internal interface SegmentCodec {
    /** Returns the file for [segment]. The same segment always gives the same content. */
    fun encode(segment: Segment): ByteArray

    /**
     * Returns the segment in [bytes]. A revision with a bad value, a merge with the wrong id or
     * text that cannot be stored is left out, alone: that takes nothing else with it.
     *
     * @throws SegmentFormatException if the file as a whole is no segment of this format.
     */
    fun decode(bytes: ByteArray): Segment
}

/**
 * A file is not a segment this code can use.
 *
 * @property isFutureFormat Whether it is a segment of a newer format, not a damaged file.
 */
internal class SegmentFormatException(
    message: String,
    val isFutureFormat: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)

/** The v1 codec: JSON, gzip, with [maxBytes] as the limit on either form. */
internal class JsonGzipCodec(
    private val maxBytes: Long = MAX_SEGMENT_BYTES,
) : SegmentCodec {
    override fun encode(segment: Segment): ByteArray {
        val out = Buffer()
        GzipSink(out).buffer().use { it.writeUtf8(segmentJson(segment)) }
        return out.readByteArray()
    }

    override fun decode(bytes: ByteArray): Segment {
        if (bytes.size > maxBytes) throw SegmentFormatException("The file is too large")
        return parse(utf8(inflate(bytes)))
    }

    private fun parse(json: String): Segment =
        try {
            requireKnownFormat(headerJson.decodeFromString<SegmentHeaderDto>(json).format)
            strictJson.decodeFromString<SegmentDto>(json).toSegment()
        } catch (cause: SerializationException) {
            throw SegmentFormatException("The file is not a valid segment", cause = cause)
        } catch (cause: IllegalArgumentException) {
            throw SegmentFormatException("The segment holds an invalid value", cause = cause)
        }

    private fun requireKnownFormat(format: Int) {
        if (format != SYNC_FORMAT) {
            val message = "This code reads format $SYNC_FORMAT, not $format"
            throw SegmentFormatException(message, isFutureFormat = format > SYNC_FORMAT)
        }
    }

    private fun utf8(bytes: ByteArray): String =
        try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (cause: CharacterCodingException) {
            throw SegmentFormatException("The file is not valid UTF-8", cause = cause)
        }

    /** Reads at most a step past [maxBytes], so a small file cannot fill memory. */
    private fun inflate(bytes: ByteArray): ByteArray {
        val inflated = Buffer()
        try {
            GzipSource(Buffer().write(bytes)).use { source ->
                var read = 0L
                while (read != -1L && inflated.size <= maxBytes) {
                    read = source.read(inflated, INFLATE_STEP)
                }
            }
        } catch (cause: IOException) {
            throw SegmentFormatException("The file is not valid gzip", cause = cause)
        }
        if (inflated.size > maxBytes) {
            throw SegmentFormatException("The file inflates to more than $maxBytes")
        }
        return inflated.readByteArray()
    }

    private companion object {
        const val INFLATE_STEP = 64L * 1024
    }
}

/** The JSON text of [revision] alone, with its parents and tags sorted. */
internal fun revisionJson(revision: Revision): String =
    strictJson.encodeToString(RevisionDto.serializer(), revision.toDto())

/** The JSON text of [segment], with parents and tags sorted so that it is canonical. */
internal fun segmentJson(segment: Segment): String =
    strictJson.encodeToString(
        SegmentDto.serializer(),
        SegmentDto(
            format = SYNC_FORMAT,
            device = segment.device.toString(),
            number = segment.number,
            revisions = segment.revisions.map(Revision::toDto),
        ),
    )

/** Every key is known and required, so a changed format cannot be half understood. */
private val strictJson =
    Json {
        encodeDefaults = false
        explicitNulls = false
    }

private val headerJson = Json { ignoreUnknownKeys = true }

private fun Revision.toDto(): RevisionDto {
    val merge = kind as? RevisionKind.AutomaticMerge
    return RevisionDto(
        id = id.toString(),
        note = noteId.toString(),
        parents = parents.sorted().map(RevisionId::toString),
        physicalMillis = timestamp.physicalMillis,
        counter = timestamp.counter,
        device = deviceId.toString(),
        merge = merge != null,
        losingParent = merge?.losingParent?.toString(),
        resolves = resolves?.toString(),
        snapshot = (content as? RevisionContent.Snapshot)?.toDto(),
    )
}

private fun RevisionContent.Snapshot.toDto(): SnapshotDto =
    SnapshotDto(
        title = title,
        body = body,
        tags = tags.map(TagName::toString).sorted(),
        pinned = pinned.value,
        pinnedStamp = pinned.stamp.toDto(),
        state = state.value.name,
        stateStamp = state.stamp.toDto(),
    )

private fun Stamp.toDto(): StampDto =
    StampDto(
        timestamp.physicalMillis,
        timestamp.counter,
        deviceId.toString(),
        revisionId.toString(),
    )

private fun SegmentDto.toSegment(): Segment {
    val dropped = mutableListOf<InvalidRevision>()
    val readable =
        revisions.mapNotNull { dto ->
            try {
                dto.toRevision()
            } catch (_: IllegalArgumentException) {
                dto.toDropped()?.let { dropped += it }
                null
            }
        }
    return Segment(DeviceId.parse(device), number, readable, dropped)
}

/** What can be kept of a revision that cannot be read: a user's text, if it says whose note. */
private fun RevisionDto.toDropped(): InvalidRevision? {
    val revision = RevisionId.parseOrNull(id)
    val noteId = NoteId.parseOrNull(note)
    return if (merge || revision == null || noteId == null) {
        null
    } else {
        InvalidRevision(
            revision,
            noteId,
            InvalidReason.MALFORMED,
            snapshot?.title.orEmpty().toStorableText(),
            snapshot?.body.orEmpty().toStorableText(),
        )
    }
}

private fun RevisionDto.toRevision(): Revision {
    val revision =
        Revision(
            id = RevisionId.parse(id),
            noteId = NoteId.parse(note),
            parents = parents.mapTo(LinkedHashSet(), RevisionId::parse),
            timestamp = HybridTimestamp(physicalMillis, counter),
            deviceId = DeviceId.parse(device),
            kind = kindOf(this),
            content = snapshot?.toSnapshot() ?: RevisionContent.Tombstone,
            resolves = resolves?.let(RevisionId::parse),
        )
    require(parents.size == revision.parents.size) { "Revision $id repeats a parent" }
    if (merge) {
        require(mergeRevisionId(revision) == revision.id) { "Merge $id does not match its hash" }
    }
    return revision
}

private fun kindOf(dto: RevisionDto): RevisionKind =
    if (dto.merge) {
        RevisionKind.AutomaticMerge(dto.losingParent?.let(RevisionId::parse))
    } else {
        require(dto.losingParent == null) { "User revision ${dto.id} names a losing parent" }
        RevisionKind.User
    }

private fun SnapshotDto.toSnapshot(): RevisionContent.Snapshot {
    require(title.isStorableText() && body.isStorableText()) { "The text cannot be stored" }
    val decodedTags = tags.mapTo(LinkedHashSet(), TagName::fromNormalised)
    require(decodedTags.size == tags.size) { "A tag is repeated" }
    return RevisionContent.Snapshot(
        title = title,
        body = body,
        tags = decodedTags,
        pinned = Stamped(pinned, pinnedStamp.toStamp()),
        state = Stamped(NoteState.valueOf(state), stateStamp.toStamp()),
    )
}

private fun StampDto.toStamp(): Stamp =
    Stamp(
        HybridTimestamp(physicalMillis, counter),
        DeviceId.parse(device),
        RevisionId.parse(revision),
    )
