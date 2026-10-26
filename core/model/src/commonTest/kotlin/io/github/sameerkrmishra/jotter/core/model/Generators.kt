package io.github.sameerkrmishra.jotter.core.model

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import kotlin.uuid.Uuid

/** Compares [a] and [b] as unsigned byte strings, a proper prefix first: the reference order. */
fun compareUnsigned(
    a: ByteArray,
    b: ByteArray,
): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        val byByte = (a[i].toInt() and 0xFF).compareTo(b[i].toInt() and 0xFF)
        if (byByte != 0) return byByte
    }
    return a.size.compareTo(b.size)
}

/** Bytes whose signed and unsigned order differ (0x7F and 0x80) and the extremes. */
private val edgeBytes: Arb<Byte> = Arb.element(0x00, 0x7F, 0x80, 0xFF).map { it.toByte() }

/** Uniformly random bytes of [size]. */
fun randomBytes(size: Int): Arb<ByteArray> = Arb.byteArray(Arb.constant(size), Arb.byte())

/**
 * Bytes of [size] that are zero except for the first and the last byte, which are [edgeBytes].
 * Only 16 values exist per size, so generated ids are often equal or differ only at the end,
 * and a 16-byte value is often a prefix of a 32-byte one.
 */
fun sparseBytes(size: Int): Arb<ByteArray> =
    Arb.bind(edgeBytes, edgeBytes) { first, last ->
        ByteArray(size).also {
            it[0] = first
            it[size - 1] = last
        }
    }

/** Bytes of a device id, from a small pool so that ties are common. */
val deviceIdBytes: Arb<ByteArray> = sparseBytes(Uuid.SIZE_BYTES)

/** Bytes of a user revision id (16) or of an automatic merge id (32), from a small pool. */
val revisionIdBytes: Arb<ByteArray> =
    Arb.choice(sparseBytes(Uuid.SIZE_BYTES), sparseBytes(RevisionId.MERGE_HASH_SIZE))

fun deviceIdOf(bytes: ByteArray): DeviceId = DeviceId.parse(bytes.toHexString())

fun revisionIdOf(bytes: ByteArray): RevisionId = RevisionId.parse(bytes.toHexString())

/** Small values, so that equal timestamps and equal millis with different counters are common. */
val timestamps: Arb<HybridTimestamp> =
    Arb.bind(
        Arb.long(0L..2L),
        Arb.int(0..2),
    ) { millis, counter -> HybridTimestamp(millis, counter) }

/** Stamps with frequent ties in each part, so every tiebreaker is exercised. */
val stamps: Arb<Stamp> =
    Arb.bind(timestamps, deviceIdBytes, revisionIdBytes) { timestamp, device, revision ->
        Stamp(timestamp, deviceIdOf(device), revisionIdOf(revision))
    }

val sampleDevice: DeviceId = DeviceId.parse("5f0c2a9e-1b3d-4e6f-8a7b-9c0d1e2f3a4b")
val sampleNote: NoteId = NoteId.parse("0192e6f0-8b4a-7c3e-9d2f-1a2b3c4d5e6f")
