package io.github.sameerkrmishra.jotter.core.model

import kotlin.jvm.JvmInline

/**
 * A tag, in normalised form. Two inputs that differ only in letter case or whitespace name the
 * same tag, so `Work`, ` work ` and `WORK` are all the tag `work`.
 *
 * [parse] normalises the input in this order:
 *
 * 1. Leading and trailing whitespace ([Char.isWhitespace]) is removed.
 * 2. Every run of whitespace inside (spaces, tabs, line breaks) becomes a single space.
 * 3. Letters are lowercased with Kotlin's locale-independent [String.lowercase].
 *
 * The result must be 1 to [MAX_LENGTH] characters (UTF-16 code units) long and must not contain
 * other control characters.
 *
 * Tags are stored lowercased instead of keeping the user's spelling and comparing them
 * case-insensitively. A display spelling would need a rule for which spelling wins when two
 * devices add `Work` and `work` concurrently, and the tag sets that the merge compares would no
 * longer be plain sets of strings. Google Keep labels map cleanly: Keep does not allow two labels
 * that differ only in case, so distinct labels stay distinct tags.
 */
@JvmInline
public value class TagName private constructor(
    // No public getter, for the same reason as in NoteId: toString() is the canonical accessor.
    private val normalised: String,
) {
    /** Returns the normalised tag, e.g. `shopping list`. */
    override fun toString(): String = normalised

    /** Factories and limits for [TagName]. */
    public companion object {
        /** The maximum length of a normalised tag, in UTF-16 code units. */
        public const val MAX_LENGTH: Int = 100

        /**
         * Normalises [text] into a tag, by the rules described on [TagName].
         *
         * @throws IllegalArgumentException if the normalised text is empty, longer than
         *   [MAX_LENGTH] or contains a control character.
         */
        public fun parse(text: String): TagName =
            requireNotNull(parseOrNull(text)) { "Not a valid tag: \"$text\"" }

        /** Like [parse], but returns `null` instead of throwing if [text] is not a valid tag. */
        public fun parseOrNull(text: String): TagName? {
            val normalised = collapseWhitespace(text).lowercase()
            val isValid =
                normalised.length in 1..MAX_LENGTH && normalised.none { it.isISOControl() }
            return if (isValid) TagName(normalised) else null
        }

        // Trims and collapses in one pass. Char.isWhitespace is used instead of a regex, whose
        // `\s` means different characters on different platforms.
        private fun collapseWhitespace(text: String): String =
            buildString {
                var spacePending = false
                for (char in text) {
                    if (char.isWhitespace()) {
                        spacePending = isNotEmpty()
                    } else {
                        if (spacePending) append(' ')
                        spacePending = false
                        append(char)
                    }
                }
            }
    }
}
