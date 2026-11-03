package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.sameerkrmishra.jotter.core.storage.HiddenHead
import io.github.sameerkrmishra.jotter.core.storage.HiddenReason
import io.github.sameerkrmishra.jotter.core.storage.InvalidReason
import io.github.sameerkrmishra.jotter.core.storage.InvalidRevision
import io.github.sameerkrmishra.jotter.core.sync.ConflictDetails

internal const val CONFLICT_HEADING = "Conflicting edits"
internal const val CONFLICT_EXPLANATION =
    "This note was changed on two devices at once, in the same lines. Jotter shows one version " +
        "and keeps the other here, so nothing is lost. Choose one, keep both as separate notes, " +
        "or combine them by hand."
internal const val WAITING_HEADING = "An edit is waiting"
internal const val WAITING_EXPLANATION =
    "An edit from another device cannot be added to this note yet, because earlier changes it " +
        "builds on have not reached this device. They may arrive with the next sync. If they " +
        "never will, for example because that device is gone, decide now what to do with this edit."
internal const val QUEUED_HEADING = "An edit waits for the conflict"
internal const val QUEUED_EXPLANATION =
    "Another edit of this note is added by itself once the conflicting edits are resolved. Its " +
        "text is here meanwhile."
internal const val SET_ASIDE_HEADING = "Text set aside"
internal const val BAD_HISTORY_EXPLANATION =
    "This text came from another device but does not fit the note's history, perhaps because " +
        "that device's clock or software was wrong. Jotter does not use it, and keeps it here so " +
        "that it is not lost. You can save it as a new note, or discard it."
internal const val MALFORMED_EXPLANATION =
    "This text arrived damaged, and only part of it could be read. Jotter does not use it, and " +
        "keeps what it could read here. You can save it as a new note, or discard it."
internal const val COLLISION_EXPLANATION =
    "Two different versions of one change arrived, and Jotter cannot tell which is right, so it " +
        "uses neither. Both are kept here. You can save either as a new note, or discard it."
internal const val ORPHAN_HEADING = "Your combined text"
internal const val ORPHAN_EXPLANATION =
    "The edits you were combining were dealt with elsewhere, perhaps on another device, so this " +
        "text no longer answers anything. It is kept here: save it as a new note, or discard it."
internal const val NOTHING_TO_REVIEW = "Nothing on this note needs attention now."
internal const val MARKERS_HINT =
    "The lines starting with <<<<<<<, ======= and >>>>>>> mark where the versions differ. " +
        "Remove them once you have chosen."
internal const val OUTDATED_HINT =
    "The note changed while you were combining, so your text was not saved. The version shown " +
        "now is above. Your text is kept: add what you need from that version, then save again."

internal const val SHOWN_VERSION = "Version shown now"
internal const val OTHER_VERSION = "Other version"
internal const val WAITING_VERSION = "Waiting edit"
internal const val YOUR_TEXT = "Your text"
internal const val KEEP_SHOWN = "Keep the shown version"
internal const val KEEP_OTHER = "Keep the other version"
internal const val USE_WAITING = "Use the waiting edit"
internal const val KEEP_BOTH = "Keep both as separate notes"
internal const val COMBINE = "Combine by hand"
internal const val SAVE_COMBINED = "Save combined text"
internal const val SAVE_AS_NEW = "Save as a new note"
internal const val DISCARD = "Discard"
internal const val DISCARD_DRAFT_TITLE = "Discard your combined text?"
internal const val DISCARD_DRAFT_TEXT =
    "What you typed here is deleted. The versions it was combined from stay as they are."
internal const val DISCARD_SET_ASIDE_TITLE = "Discard this text?"
internal const val DISCARD_SET_ASIDE_TEXT =
    "It is no longer listed on this device, and cannot be brought back here. Your notes do not " +
        "change, and other devices still list it until it is discarded there."

internal const val COMBINED_TITLE_FIELD = "combined-title"
internal const val COMBINED_BODY_FIELD = "combined-body"
internal const val DIFFERS_REGION = "differs"

internal fun conflictTag(details: ConflictDetails) = "conflict-${details.conflict.mergeId}"

internal fun waitingTag(head: HiddenHead) = "waiting-${head.revisionId}"

// Two texts of a collided id share it, so the text is part of the tag.
internal fun setAsideTag(text: InvalidRevision) =
    "set-aside-${text.revisionId}-${text.reason}-${text.body.hashCode()}"

internal fun orphanTag(draft: CombinedDraft) = "orphan-${draft.decision.targetId}"

/** A question before something that cannot be undone, and what a yes does. */
internal class Confirmation(
    val title: String,
    val text: String,
    val action: () -> Unit,
)

/**
 * The review of a note's sync problems: each with what happened in plain words, every text in
 * full, and the choices core:sync offers. Back keeps any combined text, which comes back here.
 */
@Composable
internal fun ReviewScreen(
    review: ReviewViewModel,
    onBack: () -> Unit,
) {
    var confirming by remember { mutableStateOf<Confirmation?>(null) }
    val ask = { question: Confirmation -> confirming = question }
    Column(
        Modifier.fillMaxSize().padding(spacing).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(review.title, style = MaterialTheme.typography.titleLarge)
        }
        review.message?.let {
            Message(it) { TextButton(onClick = review::dismissMessage) { Text("Dismiss") } }
        }
        review.notice?.let {
            Row {
                Text(it, Modifier.weight(1f))
                TextButton(onClick = review::dismissNotice) { Text("OK") }
            }
        }
        if (review.isDone) {
            Text(NOTHING_TO_REVIEW)
        }
        review.conflictDetails.forEach { ConflictSection(review, it, ask) }
        review.waiting.forEach { WaitingSection(review, it, ask) }
        review.orphans.forEach { OrphanSection(review, it, ask) }
        review.setAside.forEach { SetAsideSection(review, it, ask) }
    }
    confirming?.let { question ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(question.title) },
            text = { Text(question.text) },
            confirmButton = { TextButton(onClick = { confirming = null }) { Text("Keep it") } },
            dismissButton = {
                TextButton(onClick = {
                    confirming = null
                    question.action()
                }) { Text(DISCARD) }
            },
        )
    }
}

@Composable
private fun ConflictSection(
    review: ReviewViewModel,
    details: ConflictDetails,
    ask: (Confirmation) -> Unit,
) {
    val decision = Decision.Conflict(details.conflict.mergeId)
    val conflict = details.conflict
    Section(CONFLICT_HEADING, CONFLICT_EXPLANATION, conflictTag(details)) {
        Text("Where they differ", style = MaterialTheme.typography.titleSmall)
        Regions(details.markedTitle, details.shownTitle, conflict.losingTitle)
        Regions(details.markedBody, details.shownBody, conflict.losingBody)
        Version(SHOWN_VERSION, details.shownTitle, details.shownBody)
        Version(OTHER_VERSION, conflict.losingTitle, conflict.losingBody)
        Choices(review, decision, details.shownHeadId, KEEP_SHOWN to KEEP_OTHER, ask)
    }
}

@Composable
private fun WaitingSection(
    review: ReviewViewModel,
    head: HiddenHead,
    ask: (Confirmation) -> Unit,
) {
    if (head.reason == HiddenReason.OPEN_CONFLICT) {
        Section(QUEUED_HEADING, QUEUED_EXPLANATION, waitingTag(head)) {
            Version(WAITING_VERSION, head.title, head.body)
        }
    } else {
        Section(WAITING_HEADING, WAITING_EXPLANATION, waitingTag(head)) {
            val (title, body) = review.shownText
            Version(SHOWN_VERSION, title, body)
            Version(WAITING_VERSION, head.title, head.body)
            // A hidden head has a shown one, so the note is in the list.
            val shownHead = review.note?.revisionId ?: head.revisionId
            val decision = Decision.Waiting(head.revisionId)
            Choices(review, decision, shownHead, KEEP_SHOWN to USE_WAITING, ask)
        }
    }
}

@Composable
private fun OrphanSection(
    review: ReviewViewModel,
    draft: CombinedDraft,
    ask: (Confirmation) -> Unit,
) {
    Section(ORPHAN_HEADING, ORPHAN_EXPLANATION, orphanTag(draft)) {
        Version(YOUR_TEXT, draft.title, draft.body)
        Buttons(
            review,
            SAVE_AS_NEW to { review.saveDraftAsNewNote(draft) },
            Confirmation(DISCARD_DRAFT_TITLE, DISCARD_DRAFT_TEXT) { review.discardDraft(draft) },
            ask,
        )
    }
}

@Composable
private fun SetAsideSection(
    review: ReviewViewModel,
    text: InvalidRevision,
    ask: (Confirmation) -> Unit,
) {
    val explanation =
        when (text.reason) {
            InvalidReason.BAD_HISTORY -> BAD_HISTORY_EXPLANATION
            InvalidReason.MALFORMED -> MALFORMED_EXPLANATION
            InvalidReason.ID_COLLISION -> COLLISION_EXPLANATION
        }
    Section(SET_ASIDE_HEADING, explanation, setAsideTag(text)) {
        Version(SET_ASIDE_HEADING, text.title, text.body)
        Buttons(
            review,
            SAVE_AS_NEW to { review.saveAsNewNote(text) },
            Confirmation(DISCARD_SET_ASIDE_TITLE, DISCARD_SET_ASIDE_TEXT) { review.dismiss(text) },
            ask,
        )
    }
}

@Composable
private fun Section(
    heading: String,
    explanation: String,
    tag: String,
    content: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(spacing), verticalArrangement = Arrangement.spacedBy(spacing)) {
            Text(heading, style = MaterialTheme.typography.titleMedium)
            Text(explanation)
            content()
        }
    }
}
