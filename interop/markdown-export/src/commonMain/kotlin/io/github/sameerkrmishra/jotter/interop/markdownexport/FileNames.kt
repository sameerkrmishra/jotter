package io.github.sameerkrmishra.jotter.interop.markdownexport

import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.TagName
import okio.utf8Size

/**
 * The longest file name, in UTF-8 bytes. ext4 and f2fs allow 255 bytes and FAT 255 UTF-16 code
 * units, which is never more bytes. eCryptfs home directories allow only 143.
 */
internal const val MAX_FILE_NAME_BYTES: Int = 143

private const val EXTENSION = ".md"
private const val MAX_STEM_BYTES = MAX_FILE_NAME_BYTES - EXTENSION.length
private const val RESERVED_PREFIX = "_"
private const val UNTITLED = "untitled"
private const val REPLACEMENT = '-'

// FAT and Android's shared storage refuse these; `/` and `\` are the path separators.
private const val FORBIDDEN = "\"*/:<>?\\|"

// Trimmed from both ends. Windows drops trailing spaces and dots, a leading dot hides the file, a
// leading hyphen reads as an option on a command line, and `.` and `..` end up empty.
private const val TRIMMED = " .-"

// Invisible characters: format controls, bidirectional overrides that could disguise the
// extension, and noncharacters. Android's case-folding storage ignores several of them, so a name
// with one would be the same file as the name without it.
private val invisible =
    listOf(
        '\u00AD'..'\u00AD',
        '\u061C'..'\u061C',
        '\u200B'..'\u200F',
        '\u202A'..'\u202E',
        '\u2060'..'\u206F',
        '\uFDD0'..'\uFDEF',
        '\uFEFF'..'\uFEFF',
        '\uFFF9'..'\uFFFB',
        '\uFFFE'..'\uFFFF',
    )

// Windows treats these as devices, with any extension, which matters for a flash drive that is
// plugged into a Windows PC. The superscript digits count as digits there.
private val reserved = Regex("""CON|PRN|AUX|NUL|CLOCK\$|CONIN\$|CONOUT\$|(COM|LPT)[0-9¹²³]""")

private val whitespaceRun = Regex(" +")

/**
 * Gives each of [notes] a file name, and returns them in note id order. The names are safe on
 * Linux, FAT and Android storage, unique ignoring letter case, and depend only on the set of
 * notes, not on their order. See README.md for the rules.
 */
internal fun assignFileNames(notes: Collection<Note>): List<Pair<Note, String>> {
    val sorted = notes.sortedBy { it.id.toString() }
    val taken = HashSet<String>()
    // Every plain name is claimed before any disambiguated one, so a note whose title happens to
    // look like a disambiguated name keeps it.
    val plainStems = sorted.map { note -> stemOf(note.title).takeIf { taken.add(foldCase(it)) } }
    return sorted.zip(plainStems) { note, plain ->
        val stem = plain ?: disambiguate(stemOf(note.title), note.id, taken)
        note to stem + EXTENSION
    }
}

// Going through upper case first also joins `ß` with `ss` and `ſ` with `s`, as some file systems
// do. A false match only adds an id to a name.
private fun foldCase(stem: String): String = stem.uppercase().lowercase()

private fun stemOf(title: String): String {
    val safe = replaceUnsafe(title).trim(TRIMMED)
    // One byte is left for the prefix.
    val stem = safe.truncateUtf8(MAX_STEM_BYTES - RESERVED_PREFIX.length).trim(TRIMMED)
    return when {
        stem.isEmpty() -> UNTITLED
        isReserved(stem) -> RESERVED_PREFIX + stem
        else -> stem
    }
}

private fun replaceUnsafe(title: String): String {
    val safe = StringBuilder(title.length)
    title.forEachIndexed { index, char ->
        when {
            char in TagName.WHITESPACE -> safe.append(' ')
            char.isInvisible() -> Unit
            char.isControl() || char in FORBIDDEN -> safe.append(REPLACEMENT)
            title.isLoneSurrogate(index) -> safe.append(REPLACEMENT)
            else -> safe.append(char)
        }
    }
    return whitespaceRun.replace(safe, " ")
}

private fun Char.isInvisible(): Boolean = invisible.any { this in it }

private fun String.trim(chars: String): String = trim { it in chars }

private fun isReserved(stem: String): Boolean =
    reserved.matches(stem.substringBefore('.').trimEnd().uppercase())

// Cuts between code points, never inside a surrogate pair. Lone surrogates are gone by now.
private fun String.truncateUtf8(maxBytes: Int): String {
    var bytes = 0L
    var end = 0
    while (end < length) {
        val next = if (this[end].isHighSurrogate()) end + 2 else end + 1
        bytes += utf8Size(end, next)
        if (bytes > maxBytes) break
        end = next
    }
    return substring(0, end)
}

// The id makes the name unique, unless another note's title spells out this very name. The attempt
// number covers that, and the loop ends because `taken` is finite and every attempt differs.
private fun disambiguate(
    stem: String,
    id: NoteId,
    taken: MutableSet<String>,
): String =
    generateSequence(0) { it + 1 }
        .map { attempt ->
            val suffix = if (attempt == 0) " $id" else " $id $attempt"
            val room = MAX_STEM_BYTES - suffix.utf8Size().toInt()
            stem.truncateUtf8(room).trimEnd { it in TRIMMED } + suffix
        }.first { taken.add(foldCase(it)) }
