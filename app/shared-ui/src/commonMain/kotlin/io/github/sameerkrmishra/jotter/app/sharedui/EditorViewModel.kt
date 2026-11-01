package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.storage.NoSuchNoteException
import io.github.sameerkrmishra.jotter.core.storage.NoteService
import io.github.sameerkrmishra.jotter.core.storage.StaleEditException
import io.github.sameerkrmishra.jotter.core.storage.StorageBusyException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal const val SAVE_FAILED_MESSAGE = "Your text is kept here but could not be saved."

/** Why the draft cannot simply be saved. Either way the draft stays until the user decides. */
internal enum class EditorProblem {
    /** Someone else changed the note's text after this editor loaded it. */
    STALE,

    /** The note was deleted forever. */
    DELETED,
}

/**
 * One open editor, on a [Note] or on a new note. The draft in [title] and [body] belongs to the
 * user: nothing but their own choice ([reload], or closing without saving) replaces text they have
 * changed. While they have changed nothing, the editor follows the stored note.
 *
 * @param scope The app's scope. The editor's observation runs in a child of it until [close].
 * @param note The note to edit, or null for a new note, created by the first save with text.
 */
@Stable
@OptIn(ExperimentalCoroutinesApi::class)
internal class EditorViewModel(
    private val notes: NoteService,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    refreshes: MutableStateFlow<Int>,
    note: Note?,
) {
    private val observation =
        CoroutineScope(
            scope.coroutineContext + Job(scope.coroutineContext[Job]),
        )
    private val noteIds = MutableStateFlow(note?.id)

    // One save at a time: a second one must see the revision that the first one wrote.
    private val saving = Mutex()

    var title by mutableStateOf(note?.title.orEmpty())
    var body by mutableStateOf(note?.body.orEmpty())

    /** The stored note that the draft started from, which an edit is based on. */
    var base by mutableStateOf(note)
        private set

    var problem by mutableStateOf<EditorProblem?>(null)
        private set

    /** A failure that retrying may fix, until the next save succeeds. */
    var message by mutableStateOf<String?>(null)
        private set

    /** Whether a save is running, which can take 5 s if another process holds the database. */
    var isSaving by mutableStateOf(false)
        private set

    /** Whether the draft holds text that is not stored. */
    val isDirty: Boolean
        get() =
            base?.let { title != it.title || body != it.body }
                ?: (title.isNotBlank() || body.isNotBlank())

    init {
        observation.launch {
            combine(noteIds, refreshes) { id, _ -> id }
                .flatMapLatest(::observed)
                .collect(::follow)
        }
    }

    /**
     * Stores the draft. Returns whether it is safe now: false if the draft could not be stored, in
     * which case it is still here and [problem] or [message] says why.
     */
    suspend fun save(): Boolean =
        saving.withLock {
            if (!isDirty) return true
            val draftTitle = title
            val draftBody = body
            val current = base
            guarded {
                val saved =
                    withContext(io) {
                        if (current == null) {
                            notes.create(draftTitle, draftBody)
                        } else {
                            notes.edit(current.id, current.revisionId, draftTitle, draftBody)
                        }
                    }
                stored(saved, draftTitle, draftBody)
            }
        }

    /**
     * Saves until nothing is left to save, for leaving the editor: text typed during a save would
     * otherwise be dropped with the editor. Returns whether it is safe to leave, like [save].
     */
    suspend fun saveAll(): Boolean {
        while (isDirty) {
            if (!save()) return false
        }
        return true
    }

    /** Stores the draft as a new note with the same tags, for when it cannot replace this one. */
    suspend fun saveAsNewNote(): Boolean =
        saving.withLock {
            val draftTitle = title
            val draftBody = body
            val tags = base?.tags.orEmpty()
            guarded {
                val saved = withContext(io) { notes.create(draftTitle, draftBody, tags) }
                stored(saved, draftTitle, draftBody)
            }
        }

    /** Drops the draft and loads the stored note. If that fails, the draft stays. */
    suspend fun reload() {
        saving.withLock {
            val id = base?.id ?: return
            guarded {
                load(withContext(io) { notes.get(id) } ?: throw NoSuchNoteException(id))
            }
        }
    }

    /** Stops observing the note. */
    fun close() {
        observation.cancel()
    }

    private fun observed(id: NoteId?): Flow<Note?> =
        if (id == null) {
            emptyFlow()
        } else {
            // A failed read leaves the draft alone; the next refresh observes the note again.
            notes.observe(id).catch { message = READ_FAILED_MESSAGE }
        }

    private fun follow(stored: Note?) {
        val current = base ?: return
        when {
            stored == null -> problem = EditorProblem.DELETED
            // An older result that a save overtook: following it would undo the save.
            stored.updatedAt <= current.updatedAt -> Unit
            !isDirty -> load(stored)
        }
    }

    private fun load(note: Note) {
        base = note
        title = note.title
        body = note.body
        problem = null
        message = null
    }

    // Text typed during the save stays. Otherwise the draft takes the stored form of the text,
    // which replaces what SQLite cannot store, or it would count as changed for good.
    private fun stored(
        saved: Note,
        draftTitle: String,
        draftBody: String,
    ) {
        base = saved
        if (title == draftTitle) title = saved.title
        if (body == draftBody) body = saved.body
        noteIds.value = saved.id
    }

    // Whatever goes wrong, the draft stays: this runs in scopes where an exception would end the
    // app, and a disk that is full or read-only must not cost the user their text.
    @Suppress("TooGenericExceptionCaught") // The driver's exception is not in NoteService's API.
    private suspend fun guarded(block: suspend () -> Unit): Boolean {
        isSaving = true
        try {
            block()
            problem = null
            message = null
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (_: StaleEditException) {
            problem = EditorProblem.STALE
        } catch (_: NoSuchNoteException) {
            problem = EditorProblem.DELETED
        } catch (_: StorageBusyException) {
            message = BUSY_MESSAGE
        } catch (e: RuntimeException) {
            message = "$SAVE_FAILED_MESSAGE ${e.message.orEmpty()}".trim()
        } finally {
            isSaving = false
        }
        return false
    }
}
