package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import io.github.sameerkrmishra.jotter.core.storage.Highlighted

private const val PREVIEW_LINES = 6

/** The test tag of the card of the note [id]. */
internal fun cardTag(id: Any) = "note-$id"

/** A note in the grid: its title, an excerpt, its tags and a button for each of [actions]. */
@Composable
internal fun NoteCard(
    item: NoteItem,
    actions: List<NoteAction>,
    onOpen: () -> Unit,
) {
    val note = item.note
    Card(onClick = onOpen, modifier = Modifier.testTag(cardTag(note.id))) {
        Column(Modifier.padding(spacing)) {
            if (note.isPinned) {
                Text("Pinned", style = MaterialTheme.typography.labelSmall)
            }
            if (note.title.isNotEmpty()) {
                Text(note.title, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                item.snippet?.let(::annotated) ?: AnnotatedString(note.body),
                maxLines = PREVIEW_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            if (note.tags.isNotEmpty()) {
                Text(note.tags.joinToString(), style = MaterialTheme.typography.labelSmall)
            }
            FlowRow {
                actions.forEach { TextButton(onClick = it.run) { Text(it.label) } }
            }
        }
    }
}

/** [snippet] with its matches in bold. */
internal fun annotated(snippet: Highlighted): AnnotatedString =
    buildAnnotatedString {
        var end = 0
        for (match in snippet.matches) {
            append(snippet.text.substring(end, match.first))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(snippet.text.substring(match))
            }
            end = match.last + 1
        }
        append(snippet.text.substring(end))
    }
