package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.sameerkrmishra.jotter.app.sync.BackendManager
import io.github.sameerkrmishra.jotter.app.sync.SyncScheduler
import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.storage.NoteService
import io.github.sameerkrmishra.jotter.core.sync.ConflictResolver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The state of the whole app: the note list, the notes that need attention after a sync and, while
 * one is open, the editor or the review of a note. An app's composition root builds one and passes
 * it to [JotterApp].
 *
 * The view models hold their state in Compose snapshot state, not in flows, because a text field
 * must see its new value in the same frame or it loses keystrokes. Their flows are internal. That
 * also makes them [Stable]: Compose is told of every change, and an instance equals only itself.
 *
 * @param notes The notes.
 * @param conflicts What sync left for the user to decide, on the same store as [notes].
 * @param scope Where the view models run: on the UI thread, for as long as the app does. Cancel it
 *   before closing the store.
 * @param io Where blocking [NoteService] calls run, off the UI thread.
 * @param sync The backends and the scheduler that syncs with them, or null for an app that does
 *   not sync, which then shows no backend screen.
 */
@Stable
public class AppViewModel(
    private val notes: NoteService,
    private val conflicts: ConflictResolver,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val sync: SyncControls? = null,
) {
    // Each new value makes the view models read the database again.
    private val refreshes = MutableStateFlow(0)

    internal val list = NotesViewModel(notes, scope, io, refreshes)

    internal val attention = AttentionViewModel(notes, conflicts, scope, io, refreshes)

    /** The open editor, or null while the note list shows. */
    internal var editor by mutableStateOf<EditorViewModel?>(null)
        private set

    /** The open review of a note's sync problems, shown over the editor or the list. */
    internal var review by mutableStateOf<ReviewViewModel?>(null)
        private set

    /** The backend screen, while it is open. */
    internal var backends by mutableStateOf<BackendsViewModel?>(null)
        private set

    /** Whether the app syncs, so that the note list offers the backend screen. */
    internal val syncs: Boolean get() = sync != null

    /** How to exit, while the text could not be saved and the user has to decide. */
    internal var pendingExit by mutableStateOf<(() -> Unit)?>(null)
        private set

    /**
     * Reads the notes again. The flows of [NoteService] do not notice a write by another process,
     * such as the CLI, so the UI calls this when its window gains focus.
     */
    public fun refresh() {
        refreshes.update { it + 1 }
    }

    /**
     * Closes the app: keeps the review's combined texts, saves the open editor's text and then
     * calls [exit]. If either fails, [JotterApp] asks the user whether to keep editing or to exit
     * without it, so [exit] may run later or not at all.
     */
    public fun requestExit(exit: () -> Unit) {
        scope.launch {
            val kept = review?.flush() != false
            if (kept && editor?.finish() != false) exit() else pendingExit = exit
        }
    }

    /**
     * Goes back one screen, for a phone's Back: from the review, the backend screen or the editor
     * towards the note list. The editor saves first, as its own Back does, and stays open with its
     * message if that fails. Returns false on the note list, where Back leaves the app.
     */
    public fun back(): Boolean {
        when {
            review != null -> closeReview()
            backends != null -> closeBackends()
            editor != null -> leaveEditor()
            else -> return false
        }
        return true
    }

    /**
     * Stores what the user typed, and closes nothing: the editor's draft and the review's combined
     * texts. For an app that the system may stop at any time once it is in the background, as
     * Android does. A failure shows as the screen's message, as for any save.
     */
    public fun keep(): Job =
        scope.launch {
            review?.flush()
            editor?.saveAll()
        }

    /** Opens the editor on [note], or on a new note, which is created by its first save. */
    internal fun openEditor(note: Note?) {
        editor = EditorViewModel(notes, scope, io, refreshes, note)
    }

    /** Saves and closes the editor. If saving fails, it stays open and keeps the text. */
    internal fun leaveEditor() {
        val leaving = editor
        scope.launch {
            // A second Back may have closed it already, and another editor may be open by now.
            if (leaving?.finish() == true && editor === leaving) discardEditor()
        }
    }

    /**
     * Opens the review of the note [noteId]. An open editor saves first, so that the review shows
     * the text as typed. If that fails, the editor stays, with its message.
     */
    internal fun openReview(noteId: NoteId) {
        scope.launch {
            if (editor?.saveAll() != false && review?.flush() != false) {
                review?.close()
                review = ReviewViewModel(noteId, notes, conflicts, scope, io, refreshes)
            }
        }
    }

    /**
     * Closes the review, back to the editor or the list, once its combined texts are kept on disk.
     * If they cannot be, it stays open with its message.
     */
    internal fun closeReview() {
        val leaving = review ?: return
        scope.launch {
            if (leaving.flush() && review === leaving) {
                leaving.close()
                review = null
            }
        }
    }

    /** Opens the backend screen. */
    internal fun openBackends() {
        val controls = sync ?: return
        backends?.close()
        backends =
            BackendsViewModel(
                controls.manager,
                controls.scheduler,
                scope,
                hub = controls.hub,
                foldersUnavailable = controls.foldersUnavailable,
            )
    }

    /** Closes the backend screen. */
    internal fun closeBackends() {
        backends?.close()
        backends = null
    }

    /** Answers the question that [pendingExit] asks: the user stays with their text. */
    internal fun keepEditing() {
        pendingExit = null
    }

    /** Answers the question that [pendingExit] asks: the user gives up their text. */
    internal fun exitWithoutSaving() {
        pendingExit?.invoke()
        pendingExit = null
    }

    /** Closes the editor without saving. */
    internal fun discardEditor() {
        editor?.close()
        editor = null
    }
}

/**
 * What the app syncs with, for [AppViewModel]. The composition root starts [scheduler] and cancels
 * it on exit.
 *
 * @property manager The backends.
 * @property scheduler Syncs with them, and at once when the user asks.
 * @property hub The hub on this PC, or null where the app cannot run one.
 * @property foldersUnavailable Why this device cannot add a local folder as a backend, in plain
 *   words, shown instead of the folder's form; or null where it can, as on the desktop.
 */
public class SyncControls(
    public val manager: BackendManager,
    public val scheduler: SyncScheduler,
    public val hub: HubControls? = null,
    public val foldersUnavailable: String? = null,
)
