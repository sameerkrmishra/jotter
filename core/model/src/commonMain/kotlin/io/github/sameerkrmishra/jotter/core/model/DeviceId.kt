package io.github.sameerkrmishra.jotter.core.model

import kotlin.jvm.JvmInline
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The identity of one installation of Jotter (an app or CLI database), a UUID.
 *
 * It names the device's folder on every backend and is part of every user revision's [Stamp].
 * Like [NoteId], an instance always holds a valid UUID in canonical form, so equal UUIDs give
 * equal ids. Ids are ordered as unsigned byte strings, as [Stamp] requires.
 */
@JvmInline
public value class DeviceId private constructor(
    // No public getter, for the same reason as in NoteId: toString() is the canonical accessor.
    private val canonical: String,
) : Comparable<DeviceId> {
    /**
     * Compares the UUIDs as unsigned 16-byte strings.
     *
     * Comparing the canonical text gives exactly that order: it is fixed-length lowercase hex
     * with dashes at fixed positions, and `0-9` sort before `a-f`, so the first differing
     * character lies in the first differing byte and orders it the same way.
     */
    override fun compareTo(other: DeviceId): Int = canonical.compareTo(other.canonical)

    /** Returns the UUID in canonical form: 36 lowercase characters, e.g. `5f0c2a9e-...`. */
    override fun toString(): String = canonical

    /** Factories for [DeviceId]. */
    public companion object {
        /**
         * Creates a new random id (UUIDv4). Unlike [NoteId] it carries no creation time, which
         * would only reveal when the app was installed.
         */
        @OptIn(ExperimentalUuidApi::class) // generateV4() is still experimental in Kotlin 2.4.
        public fun generate(): DeviceId = DeviceId(Uuid.generateV4().toString())

        /**
         * Parses [text] as a UUID, in either the 36-character hex-and-dash form or the
         * 32-character hex-only form, ignoring letter case.
         *
         * @throws IllegalArgumentException if [text] is not a valid UUID.
         */
        public fun parse(text: String): DeviceId =
            requireNotNull(parseOrNull(text)) { "Not a valid device id: \"$text\"" }

        /** Like [parse], but returns `null` instead of throwing if [text] is not a valid UUID. */
        public fun parseOrNull(text: String): DeviceId? =
            Uuid.parseOrNull(text)?.let { DeviceId(it.toString()) }
    }
}
