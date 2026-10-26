package io.github.sameerkrmishra.jotter.core.model

import kotlin.jvm.JvmInline
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The identity of a [Revision]. ADR-0003 gives the two kinds of revision different ids:
 *
 * - a user revision has a UUIDv7 (16 bytes), from [generate];
 * - an automatic merge has a SHA-256 hash of its parents and content (32 bytes), from
 *   [fromMergeHash]. Computing that hash is the merge code's job.
 *
 * Both kinds are held as lowercase hex without dashes (32 or 64 characters), which is also their
 * text form for storage and sync. A user revision id is therefore written without the dashes a
 * [NoteId] has. One text form for both kinds keeps parsing unambiguous (the length gives the
 * kind) and makes the text order equal to the byte order (see [compareTo]).
 */
@JvmInline
public value class RevisionId private constructor(
    // No public getter, for the same reason as in NoteId: toString() is the canonical accessor.
    private val hex: String,
) : Comparable<RevisionId> {
    /** Whether this id is a content hash, i.e. names an automatic merge. */
    internal val isMergeHash: Boolean get() = hex.length == HASH_HEX_LENGTH

    /**
     * Compares the ids as unsigned byte strings, a proper prefix sorting first. Ids of the two
     * kinds can therefore be compared with each other.
     *
     * Comparing the lowercase hex text gives exactly that order: each byte is two characters,
     * `0-9` sort before `a-f`, so the first differing character lies in the first differing
     * byte and orders it the same way, and a shorter id is a prefix in both forms.
     */
    override fun compareTo(other: RevisionId): Int = hex.compareTo(other.hex)

    /** Returns the id as lowercase hex: 32 characters for a user revision, 64 for a merge. */
    override fun toString(): String = hex

    /** Factories for [RevisionId]. */
    public companion object {
        private const val UUID_HEX_LENGTH = 32
        private const val HASH_HEX_LENGTH = 64

        /** The size in bytes of the hash that names an automatic merge (SHA-256). */
        public const val MERGE_HASH_SIZE: Int = HASH_HEX_LENGTH / 2

        /** Creates the id of a new user revision: a UUIDv7 with the current time. */
        @OptIn(ExperimentalUuidApi::class) // generateV7() is still experimental in Kotlin 2.4.
        public fun generate(): RevisionId = RevisionId(Uuid.generateV7().toHexString())

        /**
         * Returns the id of an automatic merge whose content hash is [hash].
         *
         * @throws IllegalArgumentException if [hash] is not [MERGE_HASH_SIZE] bytes long.
         */
        public fun fromMergeHash(hash: ByteArray): RevisionId {
            require(hash.size == MERGE_HASH_SIZE) {
                "A merge hash has $MERGE_HASH_SIZE bytes, not ${hash.size}"
            }
            return RevisionId(hash.toHexString())
        }

        /**
         * Parses the text form produced by [toString]: 32 or 64 hex digits, ignoring letter case.
         *
         * @throws IllegalArgumentException if [text] is not such a string.
         */
        public fun parse(text: String): RevisionId =
            requireNotNull(parseOrNull(text)) { "Not a valid revision id: \"$text\"" }

        /** Like [parse], but returns `null` instead of throwing if [text] is not valid. */
        public fun parseOrNull(text: String): RevisionId? {
            val isValid =
                (text.length == UUID_HEX_LENGTH || text.length == HASH_HEX_LENGTH) &&
                    text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
            return if (isValid) RevisionId(text.lowercase()) else null
        }
    }
}
