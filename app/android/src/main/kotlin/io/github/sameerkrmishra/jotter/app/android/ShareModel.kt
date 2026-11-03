package io.github.sameerkrmishra.jotter.app.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.sameerkrmishra.jotter.core.storage.StorageBusyException
import kotlinx.coroutines.launch

/** The key in saved state of the note a share was saved as. */
internal const val SAVED_NOTE = "saved-note"

/** What a share that Android could not read is told. */
internal const val SHARE_UNREADABLE: String =
    "Jotter could not read what was shared, so nothing was saved. Share it again, or copy the " +
        "text into a note."

/** What a share is told while another process holds the database. */
internal const val NOTES_BUSY: String = "The notes are busy. Share the text again in a moment."

/** Where a share stands. */
internal sealed interface ShareState {
    /** The note is being stored. */
    data object Saving : ShareState

    /** It is stored; [leftOut] says what of the share was not taken, or is null. */
    data class Saved(
        val leftOut: String?,
    ) : ShareState

    /** Nothing was stored, and [reason] says why. */
    data class Refused(
        val reason: String,
    ) : ShareState
}

/** Where a shared note goes. */
internal interface SharedNotes {
    /**
     * Stores a new note off the main thread and returns its id.
     *
     * @throws NotesUnavailableException if the notes could not be opened.
     */
    suspend fun create(
        title: String,
        body: String,
    ): String

    /** Asks for a sync soon, as the process may end right after the share. */
    fun syncSoon()
}

/** The notes could not be opened; the message says why, for the user. */
internal class NotesUnavailableException(
    message: String,
) : Exception(message)

/**
 * Reads a share from its intent with [read]. Android unparcels every extra when the first is read,
 * so an extra of a class this app does not have, or a damaged one, fails there
 * (`BadParcelableException` and others): that share is refused, not a crash.
 */
@Suppress("TooGenericExceptionCaught") // Unparcelling fails with any RuntimeException.
internal fun readShare(read: () -> Share): ShareOutcome =
    try {
        outcomeOf(read())
    } catch (_: RuntimeException) {
        ShareOutcome.Refused(SHARE_UNREADABLE)
    }

/**
 * Stores one share as a new note, once. It lives as long as the share window, across turning the
 * phone, and [saved] outlives the process: if Android ends it after the note was stored and later
 * brings the window back, the note is not stored again.
 */
internal class ShareViewModel(
    outcome: ShareOutcome,
    private val notes: SharedNotes,
    private val saved: SavedStateHandle,
) : ViewModel() {
    /** Where the share stands. */
    var state by mutableStateOf<ShareState>(ShareState.Saving)
        private set

    init {
        state =
            when {
                outcome is ShareOutcome.Refused -> ShareState.Refused(outcome.reason)
                outcome is ShareOutcome.NewNote && saved.contains(SAVED_NOTE) ->
                    ShareState.Saved(outcome.leftOut)
                else -> ShareState.Saving
            }
        if (outcome is ShareOutcome.NewNote && state == ShareState.Saving) {
            viewModelScope.launch { state = save(outcome) }
        }
    }

    @Suppress("TooGenericExceptionCaught") // The driver's exceptions are not in NoteStore's API.
    private suspend fun save(note: ShareOutcome.NewNote): ShareState =
        try {
            saved[SAVED_NOTE] = notes.create(note.title, note.body)
            notes.syncSoon()
            ShareState.Saved(note.leftOut)
        } catch (e: NotesUnavailableException) {
            ShareState.Refused(e.message.orEmpty())
        } catch (_: StorageBusyException) {
            ShareState.Refused(NOTES_BUSY)
        } catch (e: RuntimeException) {
            ShareState.Refused("The note could not be saved: ${e.message}")
        }
}
