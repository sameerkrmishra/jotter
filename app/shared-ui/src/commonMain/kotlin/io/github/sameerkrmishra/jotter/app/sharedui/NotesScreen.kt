package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.TagName
import io.github.sameerkrmishra.jotter.core.storage.TagCount

internal const val SEARCH_FIELD = "search"

private val cardWidth = 240.dp
private val views =
    listOf(
        NoteState.ACTIVE to "Notes",
        NoteState.ARCHIVED to "Archive",
        NoteState.TRASHED to "Trash",
    )

/** The note grid, with its search field, its views and its tag filter. */
@Composable
internal fun NotesScreen(app: AppViewModel) {
    val list = app.list
    var deleting by remember { mutableStateOf<Note?>(null) }
    Column(
        Modifier.fillMaxSize().padding(spacing),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Header(list, onNewNote = { app.openEditor(null) })
        TagFilter(list.tags, list.request.tag, list::toggleTag)
        list.message?.let {
            Message(it) { TextButton(onClick = list::dismissMessage) { Text("Dismiss") } }
        }
        if (list.items.isEmpty()) {
            Text("Nothing here.")
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(cardWidth),
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            items(list.items, key = { it.note.id.toString() }) { item ->
                NoteCard(
                    item,
                    actions = list.actionsFor(item.note, onDelete = { deleting = item.note }),
                    onOpen = { app.openEditor(item.note) },
                )
            }
        }
    }
    deleting?.let { note ->
        ConfirmDelete(
            onConfirm = {
                list.deleteForever(note)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun Header(
    list: NotesViewModel,
    onNewNote: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
        OutlinedTextField(
            value = list.request.query,
            onValueChange = list::search,
            modifier = Modifier.weight(1f).testTag(SEARCH_FIELD),
            label = { Text("Search") },
            singleLine = true,
        )
        Button(onClick = onNewNote) { Text("New note") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
        views.forEach { (state, label) ->
            FilterChip(
                selected = list.request.view == state,
                onClick = { list.showView(state) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun TagFilter(
    tags: List<TagCount>,
    selected: TagName?,
    onToggle: (TagName) -> Unit,
) {
    // The selected tag stays even if its last note went to the trash, or it could not be cleared.
    val shown =
        if (selected == null || tags.any { it.tag == selected }) {
            tags
        } else {
            tags + TagCount(selected, 0)
        }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing)) {
        items(shown, key = { it.tag.toString() }) { (tag, count) ->
            FilterChip(
                selected = tag == selected,
                onClick = { onToggle(tag) },
                label = { Text("$tag ($count)") },
            )
        }
    }
}

/** A button on a card. */
internal class NoteAction(
    val label: String,
    val run: () -> Unit,
)

/** What can be done to [note] from its card, which depends on the view it is in. */
private fun NotesViewModel.actionsFor(
    note: Note,
    onDelete: () -> Unit,
): List<NoteAction> {
    val archive = NoteAction("Archive") { move(note, NoteState.ARCHIVED) }
    val restore = NoteAction("Restore") { move(note, NoteState.ACTIVE) }
    val trash = NoteAction("Trash") { move(note, NoteState.TRASHED) }
    val pin = NoteAction(if (note.isPinned) "Unpin" else "Pin") { togglePinned(note) }
    return when (note.state) {
        NoteState.ACTIVE -> listOf(pin, archive, trash)
        NoteState.ARCHIVED -> listOf(pin, restore, trash)
        NoteState.TRASHED -> listOf(restore, NoteAction("Delete forever", onDelete))
    }
}

@Composable
private fun ConfirmDelete(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete this note forever?") },
        text = { Text("This cannot be undone.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
