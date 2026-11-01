package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.TagName
import io.github.sameerkrmishra.jotter.core.storage.NoSuchNoteException
import io.github.sameerkrmishra.jotter.core.storage.NoteService
import io.github.sameerkrmishra.jotter.core.storage.StaleEditException
import io.github.sameerkrmishra.jotter.core.storage.StorageBusyException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** How long typing must pause before the draft saves itself. */
internal const val AUTOSAVE_DELAY_MS = 1_000L

internal const val INVALID_TAG_MESSAGE = "That is not a valid tag."
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
@Suppress("TooManyFunctions") // What an open note can do, kept together on purpose.
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
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

    // Counts changes to the draft, for the autosave to wait for a pause.
    private val typing = MutableStateFlow(0)

    private var titleText by mutableStateOf(note?.title.orEmpty())
    private var bodyText by mutableStateOf(note?.body.orEmpty())

    var title: String
        get() = titleText
        set(value) {
            titleText = value
            typing.update { it + 1 }
        }

    var body: String
        get() = bodyText
        set(value) {
            bodyText = value
            typing.update { it + 1 }
        }

    /** The text in the field for a new tag. */
    var tagInput by mutableStateOf("")

    /**
     * The note's tags. They are stored at once, unlike the text, because a tag change cannot lose
     * anyone's text. A new note has none stored yet, so it keeps them here until its first save.
     */
    var tags by mutableStateOf(note?.tags.orEmpty())
        private set

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
                ?: (title.isNotBlank() || body.isNotBlank() || tags.isNotEmpty())

    init {
        observation.launch {
            combine(noteIds, refreshes) { id, _ -> id }
                .flatMapLatest(::observed)
                .collect(::follow)
        }
        // Not collectLatest: typing must not cancel a save whose write may already have happened,
        // or the next edit would be based on the revision before it, and be refused as stale.
        observation.launch {
            typing.drop(1).debounce(AUTOSAVE_DELAY_MS).collect { autosave() }
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
                            notes.create(draftTitle, draftBody, tags)
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
            val draftTags = tags
            guarded {
                val saved = withContext(io) { notes.create(draftTitle, draftBody, draftTags) }
                stored(saved, draftTitle, draftBody)
            }
        }

    /** Adds the tag in [tagInput], or says why it is not a valid tag and leaves it there. */
    suspend fun addTag() {
        val tag = TagName.parseOrNull(tagInput)
        if (tag == null) {
            message = INVALID_TAG_MESSAGE
            return
        }
        changeTags(
            inMemory = { it + tag },
            inStore = { id -> notes.addTag(id, tag) },
        )
        if (tag in tags) tagInput = ""
    }

    suspend fun removeTag(tag: TagName) {
        changeTags(
            inMemory = { it - tag },
            inStore = { id -> notes.removeTag(id, tag) },
        )
    }

    // The note that a tag change returns may have someone else's text, so it does not become the
    // base: that would hide their change from the next edit.
    private suspend fun changeTags(
        inMemory: (Set<TagName>) -> Set<TagName>,
        inStore: (NoteId) -> Note,
    ) {
        saving.withLock {
            val id = base?.id
            guarded(clearsProblem = false) {
                tags = if (id == null) inMemory(tags) else withContext(io) { inStore(id) }.tags
            }
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

    // While a problem waits for the user's decision, saving again could only fail again.
    private suspend fun autosave() {
        if (problem == null) save()
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
        stored?.let { tags = it.tags }
        when {
            stored == null -> problem = EditorProblem.DELETED
            // An older result that a save overtook: following it would undo the save.
            stored.updatedAt <= current.updatedAt -> Unit
            !isDirty -> load(stored)
        }
    }

    private fun load(note: Note) {
        base = note
        titleText = note.title
        bodyText = note.body
        tags = note.tags
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
        tags = saved.tags
        if (titleText == draftTitle) titleText = saved.title
        if (bodyText == draftBody) bodyText = saved.body
        noteIds.value = saved.id
    }

    // Whatever goes wrong, the draft stays: this runs in scopes where an exception would end the
    // app, and a disk that is full or read-only must not cost the user their text.
    @Suppress("TooGenericExceptionCaught") // The driver's exception is not in NoteService's API.
    private suspend fun guarded(
        clearsProblem: Boolean = true,
        block: suspend () -> Unit,
    ): Boolean {
        isSaving = true
        try {
            block()
            if (clearsProblem) problem = null
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
