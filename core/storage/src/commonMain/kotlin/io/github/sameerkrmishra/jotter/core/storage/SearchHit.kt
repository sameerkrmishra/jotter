package io.github.sameerkrmishra.jotter.core.storage

import io.github.sameerkrmishra.jotter.core.model.Note

/**
 * A note that matched a search, with the part of it to show in the results.
 *
 * @property note The note.
 * @property snippet A short excerpt of the body around the matches, or the title if only the
 *   title matched, with the matching words marked.
 */
public data class SearchHit(
    val note: Note,
    val snippet: Highlighted,
)

/**
 * A piece of note text with the parts that matched a search marked, for the UI to highlight.
 *
 * @property text The text. An excerpt of a long body is cut at word boundaries and has `…` where
 *   text was left out.
 * @property matches The ranges of [text] that matched, as indices into [text], in order and
 *   without overlaps.
 */
public data class Highlighted(
    val text: String,
    val matches: List<IntRange>,
)
