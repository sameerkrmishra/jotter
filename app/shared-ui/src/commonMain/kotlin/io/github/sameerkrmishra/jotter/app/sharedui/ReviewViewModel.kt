package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.sameerkrmishra.jotter.core.merge.MergeResult
import io.github.sameerkrmishra.jotter.core.merge.merge
import io.github.sameerkrmishra.jotter.core.merge.withConflictMarkers
import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.RevisionId
import io.github.sameerkrmishra.jotter.core.storage.HiddenHead
import io.github.sameerkrmishra.jotter.core.storage.InvalidRevision
import io.github.sameerkrmishra.jotter.core.storage.NoteService
import io.github.sameerkrmishra.jotter.core.storage.NoteTooLargeException
import io.github.sameerkrmishra.jotter.core.storage.ResolutionDraft
import io.github.sameerkrmishra.jotter.core.storage.StorageBusyException
import io.github.sameerkrmishra.jotter.core.sync.ConflictDetails
import io.github.sameerkrmishra.jotter.core.sync.ConflictResolution
import io.github.sameerkrmishra.jotter.core.sync.ConflictResolver
import io.github.sameerkrmishra.jotter.core.sync.HiddenHeadWaitsForConflictException
import io.github.sameerkrmishra.jotter.core.sync.NoSuchConflictException
import io.github.sameerkrmishra.jotter.core.sync.NoSuchHiddenHeadException
import io.github.sameerkrmishra.jotter.core.sync.ResolveResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val ALREADY_RESOLVED_MESSAGE =
    "That was already dealt with, perhaps on another device. Nothing was changed."
internal const val WAITS_FOR_CONFLICT_MESSAGE =
    "That edit waits for the conflicting edits to be resolved. Resolve those first."
internal const val SHOWN_CHANGED_MESSAGE =
    "This note changed meanwhile, so nothing was decided. Look at the versions again."
internal const val COMBINED_TOO_LARGE_MESSAGE =
    "The combined text is too long to save. Shorten it, or copy part of it elsewhere."
internal const val RESOLVE_FAILED_MESSAGE = "Nothing was changed."
internal const val SAVED_AS_NEW_NOTE = "Saved as a new note."

/** How long typing in a combined text must pause before it is kept on disk. */
internal const val DRAFT_SAVE_MS = 1_000L

/** The labels core:sync puts on the markers of a conflict, used here for a waiting edit too. */
internal const val SHOWN_MARKER_LABEL = "shown"
internal const val OTHER_MARKER_LABEL = "other device"

/** Something on a note that the user decides with a [ConflictResolution]. */
internal sealed interface Decision {
    /** The conflicted merge, or the hidden head, that the decision answers. */
    val targetId: RevisionId

    /** The open conflict of the merge [mergeId]. */
    data class Conflict(
        val mergeId: RevisionId,
    ) : Decision {
        override val targetId: RevisionId get() = mergeId
    }

    /** The hidden head [headId], which the note cannot merge yet. */
    data class Waiting(
        val headId: RevisionId,
    ) : Decision {
        override val targetId: RevisionId get() = headId
    }
}

/**
 * Text that the user is combining by hand, to answer [decision]. It is kept on this device by
 * core:sync, so that it outlives the app, until the answer is written or the user drops it.
 *
 * @param baseId The head whose text it was combined against: the answer is refused if the note
 *   shows another one by then.
 * @param onChange Called after each change, to keep the text.
 */
@Stable
internal class CombinedDraft(
    val decision: Decision,
    baseId: RevisionId,
    title: String,
    body: String,
    private val onChange: () -> Unit,
) {
    private var titleText by mutableStateOf(title)
    private var bodyText by mutableStateOf(body)

    var title: String
        get() = titleText
        set(value) {
            titleText = value
            onChange()
        }

    var body: String
        get() = bodyText
        set(value) {
            bodyText = value
            onChange()
        }

    /** The head the text answers. A refused answer moves it to the head now shown. */
    var baseId by mutableStateOf(baseId)

    /** Whether the note showed another version when the user last tried to save. */
    var isOutdated by mutableStateOf(false)

    /** Whether conflict markers are still in the text. */
    val hasMarkers: Boolean
        get() = (lines(title) + lines(body)).any { it.startsWith("<<<<<<< ") }

    fun toStored(noteId: NoteId) =
        ResolutionDraft(
            targetId = decision.targetId,
            noteId = noteId,
            isConflict = decision is Decision.Conflict,
            baseId = baseId,
            title = title,
            body = body,
        )
}

/**
 * The review of one note's sync problems, from core:sync's `ConflictResolver`: its open conflicts
 * with both versions, the edits it does not show yet, the text sync does not use, and the texts the
 * user is combining by hand. Every text stays on screen until the user decides, and each decision
 * is one call to core:sync, which writes one revision. A decision names the version it was made
 * on, and core:sync refuses it if the note shows another one by then.
 *
 * @param noteId The note.
 * @param scope The app's scope. The review runs in a child of it until [close].
 */
@Stable
@Suppress("TooManyFunctions") // What the review of a note can do, kept together on purpose.
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
internal class ReviewViewModel(
    val noteId: NoteId,
    private val notes: NoteService,
    private val conflicts: ConflictResolver,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    refreshes: MutableStateFlow<Int>,
) {
    private val work = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))

    // Counts changes to the drafts, for keeping them once typing pauses.
    private val typing = MutableStateFlow(0)

    // What is on disk of each draft, so that only a changed one is written.
    private val kept = mutableMapOf<RevisionId, ResolutionDraft>()

    /** The note as it shows now, or null if it is not in the list. */
    var note by mutableStateOf<Note?>(null)
        private set

    var conflictDetails by mutableStateOf(emptyList<ConflictDetails>())
        private set

    var waiting by mutableStateOf(emptyList<HiddenHead>())
        private set

    var setAside by mutableStateOf(emptyList<InvalidRevision>())
        private set

    /** Whether the lists have been read once. Until then, the screen cannot say all is well. */
    var isLoaded by mutableStateOf(false)
        private set

    /** The texts being combined by hand, by what they answer. */
    var drafts by mutableStateOf(emptyMap<RevisionId, CombinedDraft>())
        private set

    /** What went wrong last, until the next decision works or the user dismisses it. */
    var message by mutableStateOf<String?>(null)
        private set

    /** What the last action did, when the screen would not show it otherwise. */
    var notice by mutableStateOf<String?>(null)
        private set

    /** Whether a decision is being written. Others wait, so that one click is one decision. */
    var isWorking by mutableStateOf(false)
        private set

    /** What to call the note: its own title or first line, or else that of a text under review. */
    val title: String
        get() =
            summaryOf(
                listOf(shownText) +
                    conflictDetails.map { it.shownTitle to it.shownBody } +
                    waiting.map { it.title to it.body } +
                    setAside.map { it.title to it.body },
            )

    /** The title and body the note shows now, empty if it is not in the list. */
    val shownText: Pair<String, String>
        get() = note?.let { it.title to it.body } ?: ("" to "")

    /**
     * Drafts whose conflict or waiting edit is gone, answered elsewhere: their text stays until the
     * user saves it as a note or drops it.
     */
    val orphans: List<CombinedDraft>
        get() {
            if (!isLoaded) return emptyList()
            val live =
                conflictDetails.map { it.conflict.mergeId } + waiting.map { it.revisionId }
            return drafts.values.filter { it.decision.targetId !in live }
        }

    /** Whether the note has nothing left to review. */
    val isDone: Boolean
        get() =
            isLoaded &&
                conflictDetails.isEmpty() &&
                waiting.isEmpty() &&
                setAside.isEmpty() &&
                drafts.isEmpty()

    init {
        work.launch {
            refreshes
                .flatMapLatest {
                    combine(
                        notes.observe(noteId),
                        conflicts.observeOpen(),
                        conflicts.observeHidden(),
                        conflicts.observeInvalid(),
                    ) { note, open, hidden, invalid ->
                        Lists(
                            note,
                            open.filter { it.noteId == noteId }.map { it.mergeId },
                            hidden.filter { it.noteId == noteId },
                            invalid.filter { it.noteId == noteId },
                        )
                    }.mapLatest { lists ->
                        // A conflict closed meanwhile has no details, and is not open any more.
                        val details = withContext(io) { lists.open.mapNotNull(conflicts::details) }
                        // The kept drafts come in once, before the screen can say all is well.
                        if (!isLoaded) restoreDrafts()
                        lists to details
                    }.catch { message = READ_FAILED_MESSAGE }
                }.collect { (lists, details) ->
                    note = lists.note
                    conflictDetails = details
                    waiting = lists.hidden
                    setAside = lists.invalid
                    isLoaded = true
                }
        }
        work.launch {
            typing.drop(1).debounce(DRAFT_SAVE_MS).collect { flush() }
        }
    }

    /** The draft for [decision], if the user is combining a text for it. */
    fun draftFor(decision: Decision): CombinedDraft? = drafts[decision.targetId]

    /**
     * Answers [decision] with [resolution], made on the version [expectedHead] showed. If the note
     * shows another by now, nothing is written: a draft is kept and marked, and its base moves to
     * the version now shown, which the screen shows beside it.
     */
    fun decide(
        decision: Decision,
        resolution: ConflictResolution,
        expectedHead: RevisionId,
    ) = act {
        val result =
            withContext(io) {
                when (decision) {
                    is Decision.Conflict -> {
                        conflicts.resolve(decision.mergeId, resolution, expectedHead)
                    }

                    is Decision.Waiting -> {
                        conflicts.settleHidden(decision.headId, resolution, expectedHead)
                    }
                }
            }
        val draft = drafts[decision.targetId]
        when (result) {
            is ResolveResult.Resolved -> {
                // core:sync dropped the kept copy in the same transaction.
                kept.remove(decision.targetId)
                drafts = drafts - decision.targetId
            }

            is ResolveResult.ShownChanged -> {
                draft?.baseId = result.shownHeadId
                draft?.isOutdated = true
                if (draft != null) typing.update { it + 1 }
                message = SHOWN_CHANGED_MESSAGE
            }

            ResolveResult.Closed -> {
                message = ALREADY_RESOLVED_MESSAGE
            }
        }
    }

    /**
     * Starts combining by hand, on the version [baseId] shows: a conflict starts from core:sync's
     * text with conflict markers, and a waiting edit from both texts in markers, as there is no
     * common text known to merge them on. The draft is kept on disk at once.
     */
    fun combineByHand(
        decision: Decision,
        baseId: RevisionId,
    ) = act {
        val (title, body) =
            when (decision) {
                is Decision.Conflict -> {
                    val mergeId = decision.mergeId
                    val details = conflictDetails.first { it.conflict.mergeId == mergeId }
                    details.markedTitle to details.markedBody
                }

                is Decision.Waiting -> {
                    val head = waiting.first { it.revisionId == decision.headId }
                    val (shownTitle, shownBody) = shownText
                    withContext(io) {
                        marked(shownTitle, head.title) to marked(shownBody, head.body)
                    }
                }
            }
        val draft = newDraft(decision, baseId, title, body)
        drafts = drafts + (decision.targetId to draft)
        keep(draft)
    }

    /** Saves [draft] as the answer to its decision. On failure, and if refused, it stays. */
    fun saveDraft(draft: CombinedDraft) {
        decide(draft.decision, ConflictResolution.Edited(draft.title, draft.body), draft.baseId)
    }

    /** Saves a draft whose decision is gone as a new note, and drops it. */
    fun saveDraftAsNewNote(draft: CombinedDraft) =
        act {
            withContext(io) { notes.create(draft.title, draft.body) }
            drop(draft)
            notice = SAVED_AS_NEW_NOTE
        }

    /** Drops [draft], on disk too. The texts it was made from are untouched. */
    fun discardDraft(draft: CombinedDraft) = act { drop(draft) }

    /** Saves text that sync does not use as a new note, once, and stops listing it. */
    fun saveAsNewNote(text: InvalidRevision) =
        act {
            withContext(io) { conflicts.saveInvalidAsNote(text) }
            notice = SAVED_AS_NEW_NOTE
        }

    /** Stops listing text that sync does not use, on this device. */
    fun dismiss(text: InvalidRevision) = act { withContext(io) { conflicts.dismissInvalid(text) } }

    fun dismissMessage() {
        message = null
    }

    fun dismissNotice() {
        notice = null
    }

    /**
     * Keeps every draft on disk now, for leaving the review or the app. Returns whether that
     * worked; if not, [message] says why and the drafts are still here.
     */
    @Suppress("TooGenericExceptionCaught") // The driver's exception is not in core:sync's API.
    suspend fun flush(): Boolean =
        try {
            keepDrafts()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            reportUnexpected(e)
            message = "$DRAFT_NOT_KEPT_MESSAGE ${e.message.orEmpty()}".trim()
            false
        }

    /** Stops observing. Call [flush] first. */
    fun close() {
        work.cancel()
    }

    private suspend fun restoreDrafts() {
        val stored = withContext(io) { conflicts.drafts() }.filter { it.noteId == noteId }
        stored.forEach { kept[it.targetId] = it }
        drafts =
            stored.associate { draft ->
                val decision =
                    if (draft.isConflict) {
                        Decision.Conflict(draft.targetId)
                    } else {
                        Decision.Waiting(draft.targetId)
                    }
                draft.targetId to newDraft(decision, draft.baseId, draft.title, draft.body)
            }
    }

    private fun newDraft(
        decision: Decision,
        baseId: RevisionId,
        title: String,
        body: String,
    ) = CombinedDraft(decision, baseId, title, body) { typing.update { it + 1 } }

    private suspend fun keepDrafts() {
        drafts.values.forEach { keep(it) }
    }

    private suspend fun keep(draft: CombinedDraft) {
        val stored = draft.toStored(noteId)
        if (kept[stored.targetId] != stored) {
            withContext(io) { conflicts.putDraft(stored) }
            kept[stored.targetId] = stored
        }
    }

    private suspend fun drop(draft: CombinedDraft) {
        val id = draft.decision.targetId
        withContext(io) { conflicts.discardDraft(id) }
        kept.remove(id)
        drafts = drafts - id
    }

    @Suppress("TooGenericExceptionCaught") // The driver's exception is not in core:sync's API.
    private fun act(block: suspend () -> Unit) {
        if (isWorking) return
        isWorking = true
        notice = null
        message = null
        work.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: NoSuchConflictException) {
                message = ALREADY_RESOLVED_MESSAGE
            } catch (_: NoSuchHiddenHeadException) {
                message = ALREADY_RESOLVED_MESSAGE
            } catch (_: HiddenHeadWaitsForConflictException) {
                message = WAITS_FOR_CONFLICT_MESSAGE
            } catch (_: StorageBusyException) {
                message = BUSY_MESSAGE
            } catch (_: NoteTooLargeException) {
                message = COMBINED_TOO_LARGE_MESSAGE
            } catch (e: RuntimeException) {
                reportUnexpected(e)
                message = "$RESOLVE_FAILED_MESSAGE ${e.message.orEmpty()}".trim()
            } finally {
                isWorking = false
            }
        }
    }

    private class Lists(
        val note: Note?,
        val open: List<RevisionId>,
        val hidden: List<HiddenHead>,
        val invalid: List<InvalidRevision>,
    )
}

internal const val DRAFT_NOT_KEPT_MESSAGE = "Your combined text could not be kept on disk."

/** [shown] and [other] in conflict markers, or the one text if they are the same. */
private fun marked(
    shown: String,
    other: String,
): String =
    when (val result = merge("", shown, other)) {
        is MergeResult.Clean -> result.text
        is MergeResult.Conflict ->
            result.withConflictMarkers(
                SHOWN_MARKER_LABEL,
                OTHER_MARKER_LABEL,
            )
    }
