package io.github.sameerkrmishra.jotter.core.model

import kotlin.jvm.JvmInline

/**
 * A tag, in normalised form. Tags are compared exactly, so `Work` and `work` are two different
 * tags, while ` Work ` and `Work` are the same one.
 *
 * [parse] normalises user input with no Unicode table lookups, so every platform and Unicode
 * version gives the same result, and devices agree on tag sets and merge hashes:
 *
 * 1. Leading and trailing whitespace is removed, and every run of whitespace inside becomes a
 *    single space (U+0020). Whitespace is exactly the fixed list of Unicode `White_Space` code
 *    points in [WHITESPACE], not [Char.isWhitespace], whose answer depends on the platform.
 * 2. Letter case is kept as typed. Case folding needs Unicode tables, which differ between
 *    platforms (older Android versions in particular), and it would lose the user's spelling.
 * 3. The result must be 1 to [MAX_LENGTH] characters (UTF-16 code units) long, must not
 *    contain a control character (Unicode category Cc: U+0000-U+001F, U+007F-U+009F), must
 *    not contain an unpaired surrogate, which has no UTF-8 encoding, must not contain the
 *    noncharacters U+FFFE or U+FFFF, which the bundled SQLite build reads back as U+FFFD, and
 *    must not start with U+FEFF, which it drops as a byte order mark. (U+FFFE, which it would
 *    read as a byte-swapped byte order mark at the start, is already excluded.)
 *
 * Unicode normalisation (NFC) is not applied, so the same word typed with combining accents
 * and with precomposed letters gives two tags. That risk is accepted. To limit duplicates, the
 * UI suggests existing tags while typing, ignoring case, and renaming a tag can merge two.
 * Google Keep labels map one to one.
 *
 * Stored and synced tags are decoded with [fromNormalised], which rejects text that is not
 * already normalised instead of rewriting it, so a decoder never silently changes a tag.
 */
@JvmInline
public value class TagName private constructor(
    // No public getter, for the same reason as in NoteId: toString() is the canonical accessor.
    private val normalised: String,
) {
    /** Returns the normalised tag, e.g. `Shopping list`. */
    override fun toString(): String = normalised

    /** Factories and limits for [TagName]. */
    public companion object {
        /** The maximum length of a normalised tag, in UTF-16 code units. */
        public const val MAX_LENGTH: Int = 100

        /**
         * The characters [parse] treats as whitespace: the Unicode `White_Space` property as of
         * Unicode 6.3, which removed U+180E (Mongolian vowel separator); it has not changed since.
         * All of them are in the Basic Multilingual Plane.
         */
        public const val WHITESPACE: String =
            "\u0009\u000A\u000B\u000C\u000D\u0020\u0085\u00A0\u1680" +
                "\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200A" +
                "\u2028\u2029\u202F\u205F\u3000"

        private const val LAST_C0_CONTROL = '\u001F'
        private val c1Controls = '\u007F'..'\u009F'

        /**
         * Normalises user input [text] into a tag, by the rules described on [TagName].
         *
         * @throws IllegalArgumentException if the normalised text is empty, longer than
         *   [MAX_LENGTH], contains a control character, an unpaired surrogate, U+FFFE or
         *   U+FFFF, or starts with U+FEFF.
         */
        public fun parse(text: String): TagName =
            requireNotNull(parseOrNull(text)) { "Not a valid tag: \"$text\"" }

        /** Like [parse], but returns `null` instead of throwing if [text] is not a valid tag. */
        public fun parseOrNull(text: String): TagName? {
            val normalised = collapseWhitespace(text)
            val isValid =
                normalised.length in 1..MAX_LENGTH &&
                    normalised.none(::isControl) &&
                    normalised.none(::isNoncharacter) &&
                    !normalised.startsWith(BYTE_ORDER_MARK) &&
                    hasOnlyPairedSurrogates(normalised)
            return if (isValid) TagName(normalised) else null
        }

        /**
         * Decodes a stored or synced tag, which must already be in normalised form.
         *
         * @throws IllegalArgumentException if [text] is not a valid, normalised tag.
         */
        public fun fromNormalised(text: String): TagName =
            requireNotNull(parseOrNull(text)?.takeIf { it.normalised == text }) {
                "Not a normalised tag: \"$text\""
            }

        // Trims and collapses in one pass.
        private fun collapseWhitespace(text: String): String =
            buildString {
                var spacePending = false
                for (char in text) {
                    if (char in WHITESPACE) {
                        spacePending = isNotEmpty()
                    } else {
                        if (spacePending) append(' ')
                        spacePending = false
                        append(char)
                    }
                }
            }

        // A lone surrogate cannot be encoded as UTF-8, so SQLite or a serialiser would replace
        // it, and devices could end up with different tags.
        private fun hasOnlyPairedSurrogates(text: String): Boolean =
            text.indices.none { index ->
                val char = text[index]
                when {
                    char.isHighSurrogate() -> text.getOrNull(index + 1)?.isLowSurrogate() != true
                    char.isLowSurrogate() -> text.getOrNull(index - 1)?.isHighSurrogate() != true
                    else -> false
                }
            }

        private fun isControl(char: Char): Boolean = char <= LAST_C0_CONTROL || char in c1Controls

        // SQLite reads these back changed: the two noncharacters as U+FFFD, and the byte order
        // mark not at all when it starts the text.
        private const val BYTE_ORDER_MARK = '\uFEFF'

        private fun isNoncharacter(char: Char): Boolean = char == '\uFFFE' || char == '\uFFFF'
    }
}
