package io.github.sameerkrmishra.jotter.app.cli

import com.github.ajalt.clikt.core.CliktError
import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.storage.NoteFilter
import io.github.sameerkrmishra.jotter.core.storage.NoteService

/**
 * Finds the note that [idOrPrefix] names: a whole id, or the start of the id of exactly one note
 * in any state. Letter case and dashes do not matter, as for [NoteId.parse].
 *
 * [NoteService] has no lookup by prefix, so a prefix reads every note. That is fine for a command
 * typed by hand. A whole id, which a script has from `jotter add`, reads only that note.
 *
 * @throws CliktError if no note, or more than one, matches.
 */
internal fun NoteService.resolve(idOrPrefix: String): Note {
    val id = NoteId.parseOrNull(idOrPrefix)
    val matches = if (id != null) listOfNotNull(get(id)) else notesWithIdPrefix(compact(idOrPrefix))
    return when (matches.size) {
        0 -> throw CliktError("jotter: no note with id $idOrPrefix")
        1 -> matches.single()
        else -> throw CliktError("jotter: id $idOrPrefix is ambiguous: ${matches.size} notes match")
    }
}

// An empty prefix would otherwise name the note of a database that has just one.
private fun NoteService.notesWithIdPrefix(prefix: String): List<Note> =
    if (prefix.isEmpty()) {
        emptyList()
    } else {
        NoteState.entries
            .flatMap { list(NoteFilter(state = it)) }
            .filter { compact(it.id.toString()).startsWith(prefix) }
    }

private fun compact(id: String): String = id.replace("-", "").lowercase()
