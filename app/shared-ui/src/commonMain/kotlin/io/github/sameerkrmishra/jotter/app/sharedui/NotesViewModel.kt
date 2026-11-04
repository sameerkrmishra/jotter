package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.sameerkrmishra.jotter.app.sync.describeStorageFailure
import io.github.sameerkrmishra.jotter.core.logging.Logger
import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.TagName
import io.github.sameerkrmishra.jotter.core.storage.Highlighted
import io.github.sameerkrmishra.jotter.core.storage.NoSuchNoteException
import io.github.sameerkrmishra.jotter.core.storage.NoteFilter
import io.github.sameerkrmishra.jotter.core.storage.NoteService
import io.github.sameerkrmishra.jotter.core.storage.StorageBusyException
import io.github.sameerkrmishra.jotter.core.storage.TagCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Searching starts at this many characters: one letter is the slowest query (core:storage). */
internal const val MIN_SEARCH_LENGTH = 2

internal const val BUSY_MESSAGE = "Another program is using the notes. Nothing changed; try again."
internal const val GONE_MESSAGE = "That note no longer exists."
internal const val MOVED_MESSAGE = "That note is no longer in the trash, so it was not deleted."
internal const val READ_FAILED_MESSAGE =
    "The notes could not be read. Try again; if it keeps happening, report it and attach the " +
        "debug log."
internal const val WRITE_FAILED_MESSAGE = "The note could not be changed."

/**
 * Keeps a failure the UI has no specific message for, which may be a bug, in the log with its
 * stack trace. The user sees plain words instead ([describeStorageFailure]).
 */
internal fun reportUnexpected(failure: Throwable) {
    uiLog.error(failure) { "Unexpected failure in the UI" }
}

private val uiLog = Logger("ui")

/**
 * What the note list shows.
 *
 * @property view Active notes, the archive or the trash.
 * @property tag Only notes with this tag, if set.
 * @property query The text in the search field, as typed.
 */
internal data class NotesRequest(
    val view: NoteState = NoteState.ACTIVE,
    val tag: TagName? = null,
    val query: String = "",
) {
    /** The text to search for, or null while the query is too short and the list shows. */
    val searchText: String? get() = query.trim().takeIf { it.length >= MIN_SEARCH_LENGTH }
}

/**
 * One card in the grid.
 *
 * @property note The note.
 * @property snippet The excerpt to show for a search hit, or null outside a search.
 */
internal data class NoteItem(
    val note: Note,
    val snippet: Highlighted? = null,
)

/**
 * The note grid: what it shows, and the actions on its notes. Every write goes through [write],
 * which turns a failure into [message].
 */
@Stable
@OptIn(ExperimentalCoroutinesApi::class)
internal class NotesViewModel(
    private val notes: NoteService,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val refreshes: MutableStateFlow<Int>,
) {
    private val requests = MutableStateFlow(NotesRequest())

    var request by mutableStateOf(requests.value)
        private set

    /** The notes to show, pinned first; search hits are in order of relevance instead. */
    var items by mutableStateOf(emptyList<NoteItem>())
        private set

    var tags by mutableStateOf(emptyList<TagCount>())
        private set

    /** What went wrong last, until the next write succeeds or the user dismisses it. */
    var message by mutableStateOf<String?>(null)
        private set

    init {
        scope.launch {
            combine(requests, refreshes) { request, _ -> request }
                .flatMapLatest(::itemsFor)
                .collect { items = it }
        }
        scope.launch {
            refreshes
                .flatMapLatest { notes.observeTags().catch { message = READ_FAILED_MESSAGE } }
                .collect { tags = it }
        }
    }

    /** Whether the "Needs attention" view shows, in place of the notes of [request]. */
    var showsAttention by mutableStateOf(false)
        private set

    fun showView(view: NoteState) {
        showsAttention = false
        update(request.copy(view = view))
    }

    fun showAttention() {
        showsAttention = true
    }

    /** Filters by [tag], or stops filtering if it is the current one. */
    fun toggleTag(tag: TagName) = update(request.copy(tag = tag.takeUnless { it == request.tag }))

    fun search(query: String) = update(request.copy(query = query))

    fun dismissMessage() {
        message = null
    }

    fun togglePinned(note: Note) = write { notes.setPinned(note.id, !note.isPinned) }

    /** Archives, trashes or restores [note]. */
    fun move(
        note: Note,
        state: NoteState,
    ) = write { notes.setState(note.id, state) }

    fun deleteForever(note: Note) = write { notes.deleteForever(note.id) }

    private fun update(new: NotesRequest) {
        request = new
        requests.value = new
    }

    // A search has no flow of its own, so a change to the notes it filters runs it again.
    private fun itemsFor(request: NotesRequest): Flow<List<NoteItem>> {
        val filter = NoteFilter(request.view, request.tag)
        val text = request.searchText
        return notes
            .observeList(filter)
            .mapLatest { list ->
                if (text == null) {
                    list.map { NoteItem(it) }
                } else {
                    withContext(io) { notes.search(text, filter) }
                        .map { NoteItem(it.note, it.snippet) }
                }
            }.catch { message = READ_FAILED_MESSAGE }
    }

    @Suppress("TooGenericExceptionCaught") // The driver's exception is not in NoteService's API.
    private fun write(block: () -> Any) {
        scope.launch {
            message =
                try {
                    withContext(io) { block() }
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (_: StorageBusyException) {
                    BUSY_MESSAGE
                } catch (_: NoSuchNoteException) {
                    refreshes.update { it + 1 }
                    GONE_MESSAGE
                } catch (_: IllegalStateException) {
                    // deleteForever, on a note that another process took out of the trash.
                    refreshes.update { it + 1 }
                    MOVED_MESSAGE
                } catch (e: RuntimeException) {
                    reportUnexpected(e)
                    "$WRITE_FAILED_MESSAGE ${describeStorageFailure(e)}"
                }
        }
    }
}
