package io.github.sameerkrmishra.jotter.interop.markdownexport

import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteId
import kotlin.time.Instant

private const val FENCE = "---\n"
private const val HEX_RADIX = 16
private const val ESCAPE_DIGITS = 4
private const val ESCAPED = "\u2028\u2029\uFEFF\uFFFE\uFFFF"
private const val REPLACEMENT_CHARACTER = '\uFFFD'

/** How every exported file of the note [id] starts, so a later export can recognise it. */
internal fun ownerMark(id: NoteId): String = "${FENCE}id: $id\n"

/**
 * Renders [note] as a Markdown file: YAML front-matter, then the body exactly as stored. No line
 * of the front-matter except its fences is `---`, so the first `---` line after the opening fence
 * ends it, whatever the body starts with.
 */
internal fun renderMarkdown(note: Note): String =
    buildString {
        append(ownerMark(note.id))
        append("title: ").append(yamlQuoted(note.title)).append('\n')
        appendTags(note)
        append("pinned: ").append(note.isPinned).append('\n')
        append("state: ").append(note.state.name.lowercase()).append('\n')
        append("created: ").append(note.createdAt).append('\n')
        val updatedAt = Instant.fromEpochMilliseconds(note.updatedAt.physicalMillis)
        append("updated: ").append(updatedAt).append('\n')
        append(FENCE)
        append(note.body)
    }

// Sorted, because the order of a set is not part of a note, and equal notes give equal files.
private fun StringBuilder.appendTags(note: Note) {
    if (note.tags.isEmpty()) {
        append("tags: []\n")
    } else {
        append("tags:\n")
        note.tags.map { it.toString() }.sorted().forEach { tag ->
            append("  - ").append(yamlQuoted(tag)).append('\n')
        }
    }
}

/**
 * Returns [text] as a YAML double-quoted scalar on one line. Quoting every title and tag is
 * simpler than deciding which plain scalars YAML would misread (`null`, `true`, `1.0`, `#x`,
 * `a: b`, `- x`, and so on). Everything YAML does not allow in a stream, or would fold, is escaped.
 * A lone surrogate has no escape that a YAML parser accepts, so it becomes U+FFFD.
 */
internal fun yamlQuoted(text: String): String =
    buildString {
        append('"')
        text.forEachIndexed { index, char ->
            when {
                char == '"' || char == '\\' -> append('\\').append(char)
                text.isLoneSurrogate(index) -> append(REPLACEMENT_CHARACTER)
                char.needsYamlEscape() -> appendYamlEscape(char)
                else -> append(char)
            }
        }
        append('"')
    }

private fun Char.needsYamlEscape(): Boolean = isISOControl() || this in ESCAPED

private fun StringBuilder.appendYamlEscape(char: Char) {
    append("\\u").append(
        char.code
            .toString(HEX_RADIX)
            .padStart(ESCAPE_DIGITS, '0')
            .uppercase(),
    )
}
