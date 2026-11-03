package io.github.sameerkrmishra.jotter.app.cli

import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.storage.SearchHit
import io.github.sameerkrmishra.jotter.core.storage.TagCount
import kotlin.time.Instant

private const val SEPARATOR = "\t"

/** An empty column, which `read` and `awk` would otherwise merge with the next one. */
private const val EMPTY = "-"

private const val MILLIS_PER_SECOND = 1_000L

/** The line of `jotter list`. */
internal fun listLine(note: Note): String =
    listOf(
        note.id,
        utcSeconds(note.updatedAt.physicalMillis),
        if (note.isPinned) "pinned" else EMPTY,
        tagList(note).ifEmpty { EMPTY },
        summary(note.title, note.body),
    ).joinToString(SEPARATOR)

/** The line of `jotter search`. */
internal fun searchLine(hit: SearchHit): String =
    listOf(hit.note.id, summary(hit.note.title, hit.note.body), oneLine(hit.snippet.text))
        .joinToString(SEPARATOR)

/** The line of `jotter tags`. */
internal fun tagLine(tag: TagCount): String = "${tag.tag}$SEPARATOR${tag.count}"

/** A line of the summary of `jotter import` and `jotter export`. */
internal fun countLine(
    name: String,
    count: Int,
): String = "$name$SEPARATOR$count"

/**
 * A line of `jotter import` on stderr. A file name or a label can hold any character, so they get
 * the same treatment as a summary.
 */
internal fun problemLine(
    severity: String,
    source: String,
    kind: String,
    detail: String,
): String =
    listOf(severity, oneLine(source), kind, oneLine(detail).ifEmpty { EMPTY })
        .joinToString(SEPARATOR)

/** The output of `jotter show`: `name: value` headers, a blank line, the body as it is. */
internal fun fullNote(note: Note): String =
    listOf(
        "id: ${note.id}",
        "title: ${oneLine(note.title)}",
        "tags: ${tagList(note)}",
        "state: ${stateName(note.state)}",
        "pinned: ${if (note.isPinned) "yes" else "no"}",
        "created: ${utcSeconds(note.createdAt.toEpochMilliseconds())}",
        "updated: ${utcSeconds(note.updatedAt.physicalMillis)}",
        "",
        note.body,
    ).joinToString("\n")

private fun stateName(state: NoteState): String =
    when (state) {
        NoteState.ACTIVE -> "active"
        NoteState.ARCHIVED -> "archived"
        NoteState.TRASHED -> "trash"
    }

/** Sorted, because a note's tags have no order of their own. */
private fun tagList(note: Note): String =
    note.tags
        .map { it.toString() }
        .sorted()
        .joinToString(",")

/** The [title], or else the first line of [body] that has text. */
internal fun summary(
    title: String,
    body: String,
): String {
    val firstLine =
        body
            .lineSequence()
            .firstOrNull(String::isNotBlank)
            .orEmpty()
    return oneLine(title.ifBlank { firstLine })
}

/** Makes [text] fit one column of one line: scripts rely on a line per note. */
internal fun oneLine(text: String): String =
    text.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim()

/** Whole seconds in UTC: a fixed width, and the same output in every time zone. */
private fun utcSeconds(millis: Long): String =
    Instant.fromEpochMilliseconds(millis.floorDiv(MILLIS_PER_SECOND) * MILLIS_PER_SECOND).toString()
