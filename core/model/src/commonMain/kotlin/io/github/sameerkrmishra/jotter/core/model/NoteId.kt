package io.github.sameerkrmishra.jotter.core.model

import kotlin.jvm.JvmInline
import kotlin.uuid.Uuid

/**
 * The identity of a note: a UUID that stays the same across edits, devices and backends.
 *
 * A [NoteId] can only be obtained through [parse] or [parseOrNull], so every instance holds a
 * valid UUID in canonical form. Two ids for the same UUID are therefore always equal, whatever
 * letter case or format the input used. [toString] returns that canonical form, which is also the
 * form to store and exchange.
 */
@JvmInline
public value class NoteId private constructor(
    private val canonical: String,
) {
    /** Returns the UUID in canonical form: 36 lowercase characters, e.g. `0192e6f0-8b4a-...`. */
    override fun toString(): String = canonical

    /** Factories for [NoteId]. */
    public companion object {
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
