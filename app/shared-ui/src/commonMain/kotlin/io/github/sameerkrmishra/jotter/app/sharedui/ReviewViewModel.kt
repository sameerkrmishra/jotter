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
import io.github.sameerkrmishra.jotter.core.storage.StorageBusyException
import io.github.sameerkrmishra.jotter.core.sync.ConflictDetails
import io.github.sameerkrmishra.jotter.core.sync.ConflictResolution
import io.github.sameerkrmishra.jotter.core.sync.ConflictResolver
import io.github.sameerkrmishra.jotter.core.sync.HiddenHeadWaitsForConflictException
import io.github.sameerkrmishra.jotter.core.sync.NoSuchConflictException
import io.github.sameerkrmishra.jotter.core.sync.NoSuchHiddenHeadException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val ALREADY_RESOLVED_MESSAGE =
    "That was already dealt with, perhaps on another device. Nothing was changed."
internal const val WAITS_FOR_CONFLICT_MESSAGE =
    "That edit waits for the conflicting edits to be resolved. Resolve those first."
internal const val COMBINED_TOO_LARGE_MESSAGE =
    "The combined text is too long to save. Shorten it, or copy part of it elsewhere."
internal const val RESOLVE_FAILED_MESSAGE = "Nothing was changed."
internal const val SAVED_AS_NEW_NOTE = "Saved as a new note."

/** The labels core:sync puts on the markers of a conflict, used here for a waiting edit too. */
internal const val SHOWN_MARKER_LABEL = "shown"
internal const val OTHER_MARKER_LABEL = "other device"

/** Something on a note that the user decides with a [ConflictResolution]. */
internal sealed interface Decision {
    /** The open conflict of the merge [mergeId]. */
    data class Conflict(
        val mergeId: RevisionId,
    ) : Decision

    /** The hidden head [headId], which the note cannot merge yet. */
    data class Waiting(
        val headId: RevisionId,
    ) : Decision
}

/** Text that the user is combining by hand, to answer [decision] with. */
@Stable
internal class CombinedDraft(
    val decision: Decision,
    private val startTitle: String,
    private val startBody: String,
) {
    var title by mutableStateOf(startTitle)
    var body by mutableStateOf(startBody)

    /** Whether the user typed anything, so that leaving would lose it. */
    val isChanged: Boolean get() = title != startTitle || body != startBody

    /** Whether conflict markers are still in the text. */
    val hasMarkers: Boolean
        get() = (lines(title) + lines(body)).any { it.startsWith("<<<<<<< ") }
}

/**
 * The review of one note's sync problems, from core:sync's `ConflictResolver`: its open conflicts
 * with both versions, the edits it does not show yet, and the text sync does not use. Every text
 * stays on screen until the user decides, and each decision is one call to core:sync, which writes
 * one revision.
 *
 * @param noteId The note.
 * @param scope The app's scope. The review runs in a child of it until [close].
 */
@Stable
@Suppress("TooManyFunctions") // What the review of a note can do, kept together on purpose.
@OptIn(ExperimentalCoroutinesApi::class)
internal class ReviewViewModel(
    val noteId: NoteId,
    private val notes: NoteService,
    private val conflicts: ConflictResolver,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    refreshes: MutableStateFlow<Int>,
) {
    private val work = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))

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

    /** The text being combined by hand, if any. */
    var draft by mutableStateOf<CombinedDraft?>(null)
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

    /** Whether the note has nothing left to review. */
    val isDone: Boolean
        get() = isLoaded && conflictDetails.isEmpty() && waiting.isEmpty() && setAside.isEmpty()

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
                        lists to withContext(io) { lists.open.mapNotNull(conflicts::details) }
                    }.catch { message = READ_FAILED_MESSAGE }
                }.collect { (lists, details) ->
                    note = lists.note
                    conflictDetails = details
                    waiting = lists.hidden
                    setAside = lists.invalid
                    isLoaded = true
                }
        }
    }

    /** Answers [decision] with [resolution]. A draft for it goes once that worked. */
    fun decide(
        decision: Decision,
        resolution: ConflictResolution,
    ) = act {
        withContext(io) {
            when (decision) {
                is Decision.Conflict -> conflicts.resolve(decision.mergeId, resolution)
                is Decision.Waiting -> conflicts.settleHidden(decision.headId, resolution)
            }
        }
        if (draft?.decision == decision) draft = null
    }

    /**
     * Starts combining by hand: a conflict starts from core:sync's text with conflict markers, and
     * a waiting edit from both texts in markers, as there is no common text known to merge them on.
     */
    fun combineByHand(decision: Decision) =
        act {
            draft =
                when (decision) {
                    is Decision.Conflict -> {
                        val details =
                            conflictDetails.first {
                                it.conflict.mergeId ==
                                    decision.mergeId
                            }
                        CombinedDraft(decision, details.markedTitle, details.markedBody)
                    }

                    is Decision.Waiting -> {
                        val head = waiting.first { it.revisionId == decision.headId }
                        val (title, body) = shownText
                        withContext(io) {
                            CombinedDraft(
                                decision,
                                marked(title, head.title),
                                marked(body, head.body),
                            )
                        }
                    }
                }
        }

    /** Saves the draft as the answer to its decision. On failure the draft stays. */
    fun saveDraft() {
        val current = draft ?: return
        decide(current.decision, ConflictResolution.Edited(current.title, current.body))
    }

    /** Drops the draft. The texts it was made from are untouched. */
    fun cancelDraft() {
        draft = null
    }

    /** Saves text that sync does not use as a new note, so that it can be used again. */
    fun saveAsNewNote(text: InvalidRevision) =
        act {
            withContext(io) { notes.create(text.title, text.body) }
            notice = SAVED_AS_NEW_NOTE
        }

    fun dismissMessage() {
        message = null
    }

    fun dismissNotice() {
        notice = null
    }

    /** Stops observing. */
    fun close() {
        work.cancel()
    }

    @Suppress("TooGenericExceptionCaught") // The driver's exception is not in core:sync's API.
    private fun act(block: suspend () -> Unit) {
        if (isWorking) return
        isWorking = true
        notice = null
        work.launch {
            message =
                try {
                    block()
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (_: NoSuchConflictException) {
                    ALREADY_RESOLVED_MESSAGE
                } catch (_: NoSuchHiddenHeadException) {
                    ALREADY_RESOLVED_MESSAGE
                } catch (_: HiddenHeadWaitsForConflictException) {
                    WAITS_FOR_CONFLICT_MESSAGE
                } catch (_: StorageBusyException) {
                    BUSY_MESSAGE
                } catch (_: NoteTooLargeException) {
                    COMBINED_TOO_LARGE_MESSAGE
                } catch (e: RuntimeException) {
                    reportUnexpected(e)
                    "$RESOLVE_FAILED_MESSAGE ${e.message.orEmpty()}".trim()
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
