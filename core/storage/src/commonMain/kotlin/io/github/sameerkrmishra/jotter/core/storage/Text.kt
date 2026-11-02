package io.github.sameerkrmishra.jotter.core.storage

/**
 * The most UTF-16 units a note's title and body can hold together. It sits below what a sync
 * segment can carry, so a note that can be written can sync (ADR-0014). A note above it from
 * before the cap stays readable, and its tags, pin and state still change, but its text cannot grow
 * or be saved unless it comes under the cap.
 */
public const val MAX_NOTE_CHARS: Int = 5_000_000

/** The title and body together are over [MAX_NOTE_CHARS]. */
public class NoteTooLargeException(
    /** Their size in UTF-16 units. */
    public val chars: Long,
) : IllegalArgumentException("A note can hold $MAX_NOTE_CHARS characters, not $chars")

/** Refuses a text that is over [MAX_NOTE_CHARS], with a [NoteTooLargeException]. */
public fun requireNoteSize(
    title: String,
    body: String,
) {
    val chars = title.length.toLong() + body.length
    if (chars > MAX_NOTE_CHARS) throw NoteTooLargeException(chars)
}

/** What [toStorableText] puts in place of a character that cannot be stored. */
private const val REPLACEMENT_CHARACTER = '\uFFFD'

/**
 * Returns this string with every character that SQLite cannot store unchanged replaced by
 * U+FFFD: unpaired surrogates, NUL, the noncharacters U+FFFE and U+FFFF, and U+FEFF as the
 * first character.
 *
 * SQLite keeps text as UTF-8, which cannot encode an unpaired surrogate, and the bundled SQLite
 * build does not agree with itself across platforms: on the JVM a lone surrogate turns into some
 * other character (a high one swallows the character after it), on Native into U+FFFD. U+FFFE
 * and U+FFFF elsewhere in the text come back as U+FFFD on both. At the start of the text, both
 * platforms read U+FEFF as a byte order mark and drop it, and read U+FFFE as a byte-swapped one:
 * it is dropped and the rest of the text comes back byte-swapped. On Native text also ends at
 * the first NUL. Text is therefore
 * made storable before it reaches the database, so that what is stored reads back equal on every
 * platform. U+FEFF anywhere but at the start round-trips and is kept.
 */
public fun String.toStorableText(): String {
    var index = firstUnstorableIndex()
    if (index < 0) return this
    val text = this
    return buildString(text.length) {
        append(text, 0, index)
        while (index < text.length) {
            val pairEnd = surrogatePairEnd(text, index)
            if (pairEnd > index) {
                append(text, index, pairEnd)
                index = pairEnd
            } else {
                val char = text[index]
                append(if (isUnstorable(char, index)) REPLACEMENT_CHARACTER else char)
                index++
            }
        }
    }
}

/**
 * Whether this string can be stored unchanged. If not, [NoteService] replaces the characters SQLite
 * cannot store with U+FFFD, so an importer can warn about it with this.
 */
public fun String.isStorableText(): Boolean = firstUnstorableIndex() < 0

/** The index of the first character [toStorableText] would replace, or -1. Allocates nothing. */
private fun String.firstUnstorableIndex(): Int {
    var index = 0
    while (index < length) {
        val pairEnd = surrogatePairEnd(this, index)
        if (pairEnd > index) {
            index = pairEnd
        } else if (isUnstorable(this[index], index)) {
            return index
        } else {
            index++
        }
    }
    return -1
}

/** If a surrogate pair starts at [index] of [text], the index after it; otherwise [index]. */
private fun surrogatePairEnd(
    text: String,
    index: Int,
): Int {
    val isPair =
        text[index].isHighSurrogate() && text.getOrNull(index + 1)?.isLowSurrogate() == true
    return if (isPair) index + 2 else index
}

/**
 * Whether [char], at [index] of its text, cannot be stored unchanged: a lone surrogate (callers
 * skip pairs first), NUL, U+FFFE, U+FFFF, or a byte order mark (U+FEFF) at the start.
 */
private fun isUnstorable(
    char: Char,
    index: Int,
): Boolean =
    char.isSurrogate() ||
        char == '\u0000' ||
        char == '\uFFFE' ||
        char == '\uFFFF' ||
        (index == 0 && char == BYTE_ORDER_MARK)

private const val BYTE_ORDER_MARK = '\uFEFF'
