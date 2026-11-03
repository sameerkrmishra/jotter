package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.storage.NoteService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The state of the whole app: the note list and, while one is open, the editor. An app's
 * composition root builds one and passes it to [JotterApp].
 *
 * The view models hold their state in Compose snapshot state, not in flows, because a text field
 * must see its new value in the same frame or it loses keystrokes. Their flows are internal. That
 * also makes them [Stable]: Compose is told of every change, and an instance equals only itself.
 *
 * @param notes The notes.
 * @param scope Where the view models run: on the UI thread, for as long as the app does. Cancel it
 *   before closing the store.
 * @param io Where blocking [NoteService] calls run, off the UI thread.
 */
@Stable
public class AppViewModel(
    private val notes: NoteService,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    // Each new value makes the view models read the database again.
    private val refreshes = MutableStateFlow(0)

    internal val list = NotesViewModel(notes, scope, io, refreshes)

    /** The open editor, or null while the note list shows. */
    internal var editor by mutableStateOf<EditorViewModel?>(null)
        private set

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
     * Closes the app: saves the open editor's text and then calls [exit]. If the text cannot be
     * saved, [JotterApp] asks the user whether to keep editing or to exit without it, so [exit] may
     * run later or not at all.
     */
    public fun requestExit(exit: () -> Unit) {
        scope.launch {
            if (editor?.finish() != false) exit() else pendingExit = exit
        }
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
