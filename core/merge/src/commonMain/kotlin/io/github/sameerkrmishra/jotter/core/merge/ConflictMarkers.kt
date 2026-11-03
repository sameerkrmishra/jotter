package io.github.sameerkrmishra.jotter.core.merge

/**
 * The text for "edit merged text" (PLAN §5): this conflict in git's marker format, for a person
 * to resolve. The same arguments always give the same text. Marker lines end in `\n` whatever the
 * note uses, and a line without a line break gets one in front of a marker.
 *
 * It is only a starting point for an edit. It is never stored as a merge and never parsed, so a
 * note that already contains marker-like lines does no harm.
 *
 * @param oursLabel Shown on the marker that opens the `ours` side; a single line.
 * @param theirsLabel Shown on the marker that closes the `theirs` side; a single line.
 */
public fun MergeResult.Conflict.withConflictMarkers(
    oursLabel: String,
    theirsLabel: String,
): String =
    buildString {
        chunks.forEach { chunk ->
            when (chunk) {
                is MergeChunk.Resolved -> {
                    append(chunk.text)
                }

                is MergeChunk.Conflicting -> {
                    endLine()
                    append("<<<<<<< ").append(oursLabel).append('\n')
                    append(chunk.ours)
                    endLine()
                    append("=======\n")
                    append(chunk.theirs)
                    endLine()
                    append(">>>>>>> ").append(theirsLabel).append('\n')
                }
            }
        }
    }

private fun StringBuilder.endLine() {
    if (isNotEmpty() && last() != '\n' && last() != '\r') append('\n')
}
