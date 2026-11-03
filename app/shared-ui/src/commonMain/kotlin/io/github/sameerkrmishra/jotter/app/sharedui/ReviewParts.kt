package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import io.github.sameerkrmishra.jotter.core.model.RevisionId
import io.github.sameerkrmishra.jotter.core.sync.ConflictResolution

/**
 * The four answers, made on the version [shownHead] shows, or the combined text while the user
 * writes it. [labels] name the first two: keeping the shown text, and taking the other.
 */
@Composable
internal fun Choices(
    review: ReviewViewModel,
    decision: Decision,
    shownHead: RevisionId,
    labels: Pair<String, String>,
    ask: (Confirmation) -> Unit,
) {
    val draft = review.draftFor(decision)
    if (draft != null) {
        CombinedEditor(review, draft, ask)
        return
    }
    val enabled = !review.isWorking
    FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing)) {
        Button(
            onClick = { review.decide(decision, ConflictResolution.KeepShown, shownHead) },
            enabled = enabled,
        ) { Text(labels.first) }
        Button(
            onClick = { review.decide(decision, ConflictResolution.KeepLosing, shownHead) },
            enabled = enabled,
        ) { Text(labels.second) }
        OutlinedButton(
            onClick = { review.decide(decision, ConflictResolution.KeepBoth, shownHead) },
            enabled = enabled,
        ) { Text(KEEP_BOTH) }
        OutlinedButton(onClick = { review.combineByHand(decision, shownHead) }, enabled = enabled) {
            Text(COMBINE)
        }
    }
}

@Composable
internal fun CombinedEditor(
    review: ReviewViewModel,
    draft: CombinedDraft,
    ask: (Confirmation) -> Unit,
) {
    if (draft.isOutdated) {
        Message(OUTDATED_HINT) {}
    }
    OutlinedTextField(
        value = draft.title,
        onValueChange = { draft.title = it },
        modifier = Modifier.fillMaxWidth().testTag(COMBINED_TITLE_FIELD),
        label = { Text("Title") },
    )
    OutlinedTextField(
        value = draft.body,
        onValueChange = { draft.body = it },
        modifier = Modifier.fillMaxWidth().testTag(COMBINED_BODY_FIELD),
        label = { Text("Note") },
    )
    if (draft.hasMarkers) {
        Text(MARKERS_HINT, style = MaterialTheme.typography.bodySmall)
    }
    Buttons(
        review,
        SAVE_COMBINED to { review.saveDraft(draft) },
        Confirmation(DISCARD_DRAFT_TITLE, DISCARD_DRAFT_TEXT) { review.discardDraft(draft) },
        ask,
    )
}

/** A button that keeps text, and one that discards it after the user said yes to [discard]. */
@Composable
internal fun Buttons(
    review: ReviewViewModel,
    keep: Pair<String, () -> Unit>,
    discard: Confirmation,
    ask: (Confirmation) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
        Button(onClick = keep.second, enabled = !review.isWorking) { Text(keep.first) }
        TextButton(onClick = { ask(discard) }, enabled = !review.isWorking) { Text(DISCARD) }
    }
}

/** One version of the note, in full. */
@Composable
internal fun Version(
    label: String,
    title: String,
    body: String,
) {
    HorizontalDivider()
    Text(label, style = MaterialTheme.typography.labelLarge)
    if (title.isNotEmpty()) {
        Text(title, style = MaterialTheme.typography.titleSmall)
    }
    if (body.isNotEmpty() || title.isEmpty()) {
        TextOrNothing(body)
    }
}

/**
 * Where the versions agree, as plain text, and where they differ, as the two sides in colours. If
 * the regions cannot be told for certain, the text with markers shows as it is.
 */
@Composable
internal fun Regions(
    marked: String,
    shown: String,
    other: String,
) {
    val regions = regionsOf(marked, shown, other)
    if (regions == null) {
        Text(marked)
        return
    }
    // A text the versions agree on, such as a title nobody changed, needs no second showing.
    if (regions.none { it is ConflictRegion.Differs }) return
    regions.forEach { region ->
        if (region is ConflictRegion.Differs) {
            Column(Modifier.fillMaxWidth().testTag(DIFFERS_REGION)) {
                Side("Shown now", region.shown, MaterialTheme.colorScheme.primaryContainer)
                Side("Other version", region.other, MaterialTheme.colorScheme.tertiaryContainer)
            }
        } else {
            Text((region as ConflictRegion.Same).text.removeSuffix("\n"))
        }
    }
}

@Composable
internal fun Side(
    label: String,
    text: String,
    color: Color,
) {
    Surface(color = color, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = spacing)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            TextOrNothing(text.removeSuffix("\n"))
        }
    }
}

@Composable
internal fun TextOrNothing(text: String) {
    if (text.isEmpty()) {
        Text("(nothing)", fontStyle = FontStyle.Italic)
    } else {
        Text(text)
    }
}
