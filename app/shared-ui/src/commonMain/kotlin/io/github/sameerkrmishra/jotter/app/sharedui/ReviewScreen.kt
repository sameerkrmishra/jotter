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
import androidx.compose.material3.OutlinedButton
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
        "that it is not lost. You can save it as a new note."
internal const val MALFORMED_EXPLANATION =
    "This text arrived damaged, and only part of it could be read. Jotter does not use it, and " +
        "keeps what it could read here. You can save it as a new note."
internal const val COLLISION_EXPLANATION =
    "Two different versions of one change arrived, and Jotter cannot tell which is right, so it " +
        "uses neither. Both are kept here. You can save either as a new note."
internal const val NOTHING_TO_REVIEW = "Nothing on this note needs attention now."
internal const val MARKERS_HINT =
    "The lines starting with <<<<<<<, ======= and >>>>>>> mark where the versions differ. " +
        "Remove them once you have chosen."

internal const val SHOWN_VERSION = "Version shown now"
internal const val OTHER_VERSION = "Other version"
internal const val WAITING_VERSION = "Waiting edit"
internal const val KEEP_SHOWN = "Keep the shown version"
internal const val KEEP_OTHER = "Keep the other version"
internal const val USE_WAITING = "Use the waiting edit"
internal const val KEEP_BOTH = "Keep both as separate notes"
internal const val COMBINE = "Combine by hand"
internal const val SAVE_COMBINED = "Save combined text"
internal const val SAVE_AS_NEW = "Save as a new note"

internal const val COMBINED_TITLE_FIELD = "combined-title"
internal const val COMBINED_BODY_FIELD = "combined-body"
internal const val DIFFERS_REGION = "differs"

internal fun conflictTag(details: ConflictDetails) = "conflict-${details.conflict.mergeId}"

internal fun waitingTag(head: HiddenHead) = "waiting-${head.revisionId}"

internal fun setAsideTag(text: InvalidRevision) = "set-aside-${text.revisionId}"

/**
 * The review of a note's sync problems: each with what happened in plain words, every text in
 * full, and the choices core:sync offers. Back asks first if a combined text would be lost.
 */
@Composable
internal fun ReviewScreen(
    review: ReviewViewModel,
    onBack: () -> Unit,
) {
    var leaving by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(spacing).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
            TextButton(onClick = {
                if (review.draft?.isChanged ==
                    true
                ) {
                    leaving = true
                } else {
                    onBack()
                }
            }) {
                Text("Back")
            }
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
        review.conflictDetails.forEach { ConflictSection(review, it) }
        review.waiting.forEach { WaitingSection(review, it) }
        review.setAside.forEach { SetAsideSection(review, it) }
    }
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text("Discard the combined text?") },
            text = { Text("Nothing has been decided yet. Both versions stay as they are.") },
            confirmButton = { TextButton(onClick = { leaving = false }) { Text("Keep editing") } },
            dismissButton = {
                TextButton(onClick = {
                    leaving = false
                    onBack()
                }) { Text("Discard") }
            },
        )
    }
}

@Composable
private fun ConflictSection(
    review: ReviewViewModel,
    details: ConflictDetails,
) {
    val decision = Decision.Conflict(details.conflict.mergeId)
    val conflict = details.conflict
    Section(CONFLICT_HEADING, CONFLICT_EXPLANATION, conflictTag(details)) {
        Text("Where they differ", style = MaterialTheme.typography.titleSmall)
        Regions(details.markedTitle, details.shownTitle, conflict.losingTitle)
        Regions(details.markedBody, details.shownBody, conflict.losingBody)
        Version(SHOWN_VERSION, details.shownTitle, details.shownBody)
        Version(OTHER_VERSION, conflict.losingTitle, conflict.losingBody)
        Choices(review, decision, KEEP_SHOWN, KEEP_OTHER)
    }
}

@Composable
private fun WaitingSection(
    review: ReviewViewModel,
    head: HiddenHead,
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
            Choices(review, Decision.Waiting(head.revisionId), KEEP_SHOWN, USE_WAITING)
        }
    }
}

@Composable
private fun SetAsideSection(
    review: ReviewViewModel,
    text: InvalidRevision,
) {
    val explanation =
        when (text.reason) {
            InvalidReason.BAD_HISTORY -> BAD_HISTORY_EXPLANATION
            InvalidReason.MALFORMED -> MALFORMED_EXPLANATION
            InvalidReason.ID_COLLISION -> COLLISION_EXPLANATION
        }
    Section(SET_ASIDE_HEADING, explanation, setAsideTag(text)) {
        Version(SET_ASIDE_HEADING, text.title, text.body)
        OutlinedButton(onClick = { review.saveAsNewNote(text) }, enabled = !review.isWorking) {
            Text(SAVE_AS_NEW)
        }
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
