package io.github.sameerkrmishra.jotter.interop.markdownexport

/** Whether this is a control character (Unicode category Cc). */
internal fun Char.isControl(): Boolean = this < ' ' || this in '\u007F'..'\u009F'

/** Whether the character at [index] is half of a surrogate pair whose other half is missing. */
internal fun String.isLoneSurrogate(index: Int): Boolean {
    val char = this[index]
    return when {
        char.isHighSurrogate() -> getOrNull(index + 1)?.isLowSurrogate() != true
        char.isLowSurrogate() -> getOrNull(index - 1)?.isHighSurrogate() != true
        else -> false
    }
}
