package io.github.sameerkrmishra.jotter.app.sharedui

/** A part of a conflicted text, as the review shows it. */
internal sealed interface ConflictRegion {
    /** Text both versions agree on, or that merged. */
    data class Same(
        val text: String,
    ) : ConflictRegion

    /** Lines where the versions disagree: what the shown version and the other one have here. */
    data class Differs(
        val shown: String,
        val other: String,
    ) : ConflictRegion
}

// The markers core:sync puts in ConflictDetails, with its labels (ConflictResolver.SHOWN_LABEL and
// LOSING_LABEL). If those change, every text reads as agreeing, which ReviewScreenTest notices.
private const val OPEN_MARKER = "<<<<<<< $SHOWN_MARKER_LABEL\n"
private const val MIDDLE_MARKER = "=======\n"
private const val CLOSE_MARKER = ">>>>>>> $OTHER_MARKER_LABEL\n"
private val markers = setOf(OPEN_MARKER, MIDDLE_MARKER, CLOSE_MARKER).map { it.trimEnd() }

/**
 * The regions of [marked], the text with conflict markers that core:sync builds from the two
 * versions [shown] and [other], or null if they cannot be told apart for certain.
 *
 * core:merge's markers are meant for a person, not to be parsed. They can be read back here only
 * because every line between them comes from one of the versions: so if neither version has a line
 * that looks like a marker, each marker line is one. Otherwise this gives up, and the review shows
 * the versions without the regions. Nothing is lost either way, as both versions show in full.
 */
internal fun regionsOf(
    marked: String,
    shown: String,
    other: String,
): List<ConflictRegion>? {
    if ((lines(shown) + lines(other)).any { it.trimEnd('\r', '\n') in markers }) return null
    val reader = RegionReader()
    lines(marked).forEach(reader::read)
    return reader.finish()
}

/** Reads a marked text line by line, from outside a conflict to its shown side, other side, out. */
private class RegionReader {
    private val regions = mutableListOf<ConflictRegion>()
    private val same = StringBuilder()
    private val shown = StringBuilder()
    private val other = StringBuilder()
    private var side: StringBuilder = same

    fun read(line: String) {
        when {
            side === same && line == OPEN_MARKER -> {
                flushSame()
                side = shown
            }

            side === shown && line == MIDDLE_MARKER -> {
                side = other
            }

            side === other && line == CLOSE_MARKER -> {
                regions += ConflictRegion.Differs(shown.toString(), other.toString())
                shown.clear()
                other.clear()
                side = same
            }

            else -> {
                side.append(line)
            }
        }
    }

    /** The regions, or null if a conflict was left open. */
    fun finish(): List<ConflictRegion>? {
        flushSame()
        return regions.takeIf { side === same }
    }

    private fun flushSame() {
        if (same.isNotEmpty()) regions += ConflictRegion.Same(same.toString())
        same.clear()
    }
}

/** [text] cut after each `\n`, `\r\n` or lone `\r`, as core:merge cuts it, breaks kept. */
internal fun lines(text: String): List<String> {
    val result = mutableListOf<String>()
    var start = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '\n' || c == '\r') {
            if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
            result += text.substring(start, i + 1)
            start = i + 1
        }
        i++
    }
    if (start < text.length) result += text.substring(start)
    return result
}
