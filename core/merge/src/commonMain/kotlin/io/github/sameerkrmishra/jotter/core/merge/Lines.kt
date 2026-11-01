package io.github.sameerkrmishra.jotter.core.merge

/**
 * Splits [text] into lines that keep their own line break, so joining them gives [text] back
 * exactly.
 *
 * `\n`, `\r\n` and a lone `\r` each end a line, and `\r\n` is one break. A last line without a
 * break is a line of its own, so it differs from the same line with one. Nothing else ends a
 * line: U+2028, U+0085 and form feed stay inside it, because this must not depend on what a
 * platform's Unicode tables or `lines()` think a line is. Text is only ever cut after a `\n` or a
 * `\r`, neither of which is half of a surrogate pair.
 */
internal fun splitLines(text: String): List<String> {
    val lines = ArrayList<String>()
    var start = 0
    var index = 0
    while (index < text.length) {
        val char = text[index]
        index += 1
        if (char == '\r' && index < text.length && text[index] == '\n') index += 1
        if (char == '\n' || char == '\r') {
            lines += text.substring(start, index)
            start = index
        }
    }
    if (start < text.length) lines += text.substring(start)
    return lines
}
