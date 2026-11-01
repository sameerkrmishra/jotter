package io.github.sameerkrmishra.jotter.interop.markdownexport

import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.TagName

/**
 * Which of the given notes to export. By default all of them, in every state: an export is a copy
 * of the user's data, so leaving something out is the caller's explicit choice.
 *
 * @property states Only notes in one of these states. Remove [NoteState.TRASHED] to leave out the
 *   trash.
 * @property tag If set, only notes that have this tag. Tags compare exactly, as everywhere else.
 */
public data class ExportFilter(
    val states: Set<NoteState> = NoteState.entries.toSet(),
    val tag: TagName? = null,
) {
    internal fun matches(note: Note): Boolean =
        note.state in states && (tag == null || tag in note.tags)
}

/**
 * One exported note. Only [exportMarkdown] creates these, so every [fileName] is known to be safe
 * and [writeExport] cannot write outside its directory.
 *
 * @property fileName A single path segment ending in `.md`, safe on Linux, FAT and Android storage.
 * @property content The whole file: YAML front-matter, then the note's body unchanged.
 */
@ConsistentCopyVisibility
public data class ExportedFile internal constructor(
    val fileName: String,
    val content: String,
)

/**
 * Exports the [notes] that match [filter] as one Markdown file each, sorted by file name. The
 * result depends only on the set of matching notes, not on their order. File names are unique even
 * ignoring letter case; a note may get its id in the name to make that so.
 */
public fun exportMarkdown(
    notes: Collection<Note>,
    filter: ExportFilter = ExportFilter(),
): List<ExportedFile> =
    assignFileNames(notes.filter(filter::matches))
        .map { (note, fileName) -> ExportedFile(fileName, renderMarkdown(note)) }
        .sortedBy { it.fileName }
