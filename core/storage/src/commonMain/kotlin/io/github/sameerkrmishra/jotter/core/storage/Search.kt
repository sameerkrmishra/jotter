package io.github.sameerkrmishra.jotter.core.storage

import io.github.sameerkrmishra.jotter.core.model.TagName

/** Marks where a match starts in the text that `highlight()` and `snippet()` return (Note.sq). */
private const val MATCH_START = '\uE000'

/** Marks where a match ends. */
private const val MATCH_END = '\uE001'

/** What separates words in a query: [TagName.WHITESPACE] and NUL. */
private const val SEPARATORS = TagName.WHITESPACE + "\u0000"

/**
 * Turns what a person typed into an FTS5 query, or returns null if [text] has no words.
 *
 * User text is never FTS5 syntax: the text is split at whitespace and each piece becomes a quoted
 * string, with any `"` doubled, so `AND`, `NEAR`, `*`, `-`, `:` and parentheses match literally.
 * FTS5 ANDs the strings. The last one matches as a prefix (`"wor"*` finds "world"), so results
 * appear while the person types. Whitespace is [TagName.WHITESPACE], for the same reason as in
 * tags: it does not depend on platform Unicode tables.
 *
 * A piece without any letter or digit, such as `-`, holds no token; FTS5 ignores it next to
 * others and matches nothing if it is alone. NUL also separates words: FTS5 reads the query as
 * a C string, where NUL would end it inside a quoted string, a syntax error.
 */
internal fun ftsQuery(text: String): String? {
    // Separators first: toStorableText would turn NUL into U+FFFD.
    val spaced = text.map { if (it in SEPARATORS) ' ' else it }.joinToString("")
    val words = spaced.toStorableText().split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return null
    return words.joinToString(separator = " ", postfix = "*") { word ->
        "\"" + word.replace("\"", "\"\"") + "\""
    }
}

/**
 * Removes the match markers from [marked], the output of FTS5's `highlight()` or `snippet()`
 * (Note.sq), and returns the text with the ranges they enclosed.
 *
 * A note's own U+E000 and U+E001 characters (Unicode private use, so rare) cannot be told apart
 * from the markers. They are removed too, and may move, join or drop highlights in that note's
 * snippet, but the ranges are always valid: within the text, in order and not empty.
 */
internal fun parseHighlighted(marked: String): Highlighted {
    val text = StringBuilder(marked.length)
    val matches = mutableListOf<IntRange>()
    var start = -1
    for (char in marked) {
        when (char) {
            MATCH_START -> start = text.length
            MATCH_END -> {
                if (start in 0 until text.length) matches += start until text.length
                start = -1
            }
            else -> text.append(char)
        }
    }
    return Highlighted(text.toString(), matches)
}

/** The snippet for a hit: the body excerpt if the body matched, otherwise the title. */
internal fun snippetOf(
    titleHighlight: String?,
    bodySnippet: String?,
): Highlighted {
    val body = parseHighlighted(bodySnippet.orEmpty())
    return if (body.matches.isNotEmpty()) body else parseHighlighted(titleHighlight.orEmpty())
}
