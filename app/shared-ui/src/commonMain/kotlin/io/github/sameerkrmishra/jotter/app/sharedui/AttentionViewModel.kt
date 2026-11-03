package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.storage.HiddenHead
import io.github.sameerkrmishra.jotter.core.storage.HiddenReason
import io.github.sameerkrmishra.jotter.core.storage.InvalidRevision
import io.github.sameerkrmishra.jotter.core.storage.NoteService
import io.github.sameerkrmishra.jotter.core.storage.OpenConflict
import io.github.sameerkrmishra.jotter.core.sync.ConflictResolver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val CONFLICT_LABEL = "conflicting edits"
internal const val WAITING_LABEL = "an edit is waiting"
internal const val SET_ASIDE_LABEL = "text set aside"
internal const val UNTITLED = "Untitled note"

/**
 * What sync left for the user to look at on one note (core:sync's `ConflictResolver`). A note
 * appears only if one of the lists has something.
 *
 * @property noteId The note. It may no longer be in the list, if none of its text counts.
 * @property title What to call the note: its title or first line, or that of the text here.
 * @property conflicts Open conflicts: two edits of the same lines.
 * @property hidden Edits the note does not show yet, with their text.
 * @property invalid Text that sync does not use, kept so that it is not lost.
 */
internal data class NoteAttention(
    val noteId: NoteId,
    val title: String,
    val conflicts: List<OpenConflict>,
    val hidden: List<HiddenHead>,
    val invalid: List<InvalidRevision>,
) {
    /** A few words for the card and the editor, such as "conflicting edits". */
    val label: String
        get() =
            listOfNotNull(
                CONFLICT_LABEL.takeIf { conflicts.isNotEmpty() },
                // A head behind an open conflict merges once that is resolved: nothing more to do.
                WAITING_LABEL.takeIf {
                    hidden.any { head ->
                        head.reason == HiddenReason.MISSING_ANCESTOR || conflicts.isEmpty()
                    }
                },
                SET_ASIDE_LABEL.takeIf { invalid.isNotEmpty() },
            ).joinToString(", ")
}

/**
 * The notes that need the user's attention after a sync, from core:sync's lists of open conflicts,
 * hidden heads and text that does not count. Nothing on these lists may stay out of sight, which
 * is why the grid marks each note and the "Needs attention" view lists them all.
 */
@Stable
@OptIn(ExperimentalCoroutinesApi::class)
internal class AttentionViewModel(
    private val notes: NoteService,
    private val conflicts: ConflictResolver,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    refreshes: MutableStateFlow<Int>,
) {
    /** The notes that need attention, oldest note first. */
    var entries by mutableStateOf(emptyList<NoteAttention>())
        private set

    /** The same, by note. */
    var byNote by mutableStateOf(emptyMap<NoteId, NoteAttention>())
        private set

    /** Whether the lists could not be read. What was read before stays, until a refresh works. */
    var failed by mutableStateOf(false)
        private set

    init {
        scope.launch {
            refreshes
                .flatMapLatest {
                    combine(
                        conflicts.observeOpen(),
                        conflicts.observeHidden(),
                        conflicts.observeInvalid(),
                    ) { open, hidden, invalid -> Triple(open, hidden, invalid) }
                        .mapLatest { (open, hidden, invalid) -> group(open, hidden, invalid) }
                        .catch { failed = true }
                }.collect {
                    entries = it
                    byNote = it.associateBy(NoteAttention::noteId)
                    failed = false
                }
        }
    }

    private suspend fun group(
        open: List<OpenConflict>,
        hidden: List<HiddenHead>,
        invalid: List<InvalidRevision>,
    ): List<NoteAttention> {
        val ids = (open.map { it.noteId } + hidden.map { it.noteId } + invalid.map { it.noteId })
        return ids.distinct().sortedBy { it.toString() }.map { id ->
            val texts =
                open.filter { it.noteId == id }.map { it.losingTitle to it.losingBody } +
                    hidden.filter { it.noteId == id }.map { it.title to it.body } +
                    invalid.filter { it.noteId == id }.map { it.title to it.body }
            val note = withContext(io) { notes.get(id) }
            NoteAttention(
                noteId = id,
                title = summaryOf(note?.let { listOf(it.title to it.body) }.orEmpty() + texts),
                conflicts = open.filter { it.noteId == id },
                hidden = hidden.filter { it.noteId == id },
                invalid = invalid.filter { it.noteId == id },
            )
        }
    }
}

/** The first title, or else first line of a body, that has text among [texts]. */
internal fun summaryOf(texts: List<Pair<String, String>>): String =
    texts.firstNotNullOfOrNull { (title, body) ->
        title.trim().ifEmpty { body.lineSequence().firstOrNull(String::isNotBlank)?.trim() }
    } ?: UNTITLED
