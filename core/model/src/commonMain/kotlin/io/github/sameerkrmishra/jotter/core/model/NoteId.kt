package io.github.sameerkrmishra.jotter.core.model

import kotlin.jvm.JvmInline
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The identity of a note: a UUID that stays the same across edits, devices and backends.
 *
 * New ids come from [generate], which creates a UUIDv7: its leading bits are the creation time, so
 * ids are unique without coordination between devices and sort roughly by creation order. Stored
 * or received ids come back through [parse] or [parseOrNull], which accept any UUID version.
 * Every instance therefore holds a valid UUID in canonical form, and two ids for the same UUID
 * are always equal, whatever letter case or format the input used. [toString] returns that
 * canonical form, which is also the form to store and exchange.
 */
@JvmInline
public value class NoteId private constructor(
    // Intentionally no public getter: toString() is the canonical accessor. A value class getter
    // is only called from Java and reflection, so it would sit uncovered forever.
    private val canonical: String,
) {
    /** Returns the UUID in canonical form: 36 lowercase characters, e.g. `0192e6f0-8b4a-...`. */
    override fun toString(): String = canonical

    /** Factories for [NoteId]. */
    public companion object {
        /** Creates a new, unique id: a UUIDv7 with the current time and random bits. */
        @OptIn(ExperimentalUuidApi::class) // generateV7() is still experimental in Kotlin 2.4.
        public fun generate(): NoteId = NoteId(Uuid.generateV7().toString())

        /**
         * Parses [text] as a UUID, in either the 36-character hex-and-dash form or the
         * 32-character hex-only form, ignoring letter case.
         *
         * @throws IllegalArgumentException if [text] is not a valid UUID.
         */
        public fun parse(text: String): NoteId =
            requireNotNull(parseOrNull(text)) { "Not a valid note id: \"$text\"" }

        /** Like [parse], but returns `null` instead of throwing if [text] is not a valid UUID. */
        public fun parseOrNull(text: String): NoteId? =
            Uuid.parseOrNull(text)?.let { NoteId(it.toString()) }
    }
}
