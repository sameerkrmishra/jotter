package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.InputChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import io.github.sameerkrmishra.jotter.app.sharedui.markdown.MarkdownDocument
import io.github.sameerkrmishra.jotter.app.sharedui.markdown.MarkdownPreview
import kotlinx.coroutines.launch

internal const val TITLE_FIELD = "title"
internal const val BODY_FIELD = "body"
internal const val TAG_FIELD = "tag"
internal const val STALE_MESSAGE =
    "This note was changed elsewhere. Your text is kept here and has not been saved."
internal const val DELETED_MESSAGE =
    "This note was deleted forever. Your text is kept here and has not been saved."

/** The editor, with the body as text or as a preview. Back saves, or stays if it cannot. */
@Composable
internal fun EditorScreen(
    editor: EditorViewModel,
    app: AppViewModel,
) {
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().padding(spacing).onPreviewKeyEvent { event ->
            val saveKeys = event.key == Key.S && event.isCtrlPressed
            if (saveKeys && event.type == KeyEventType.KeyDown) scope.launch { editor.save() }
            saveKeys
        },
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
            TextButton(onClick = app::leaveEditor) { Text("Back") }
            Button(onClick = { scope.launch { editor.save() } }, enabled = editor.isDirty) {
                Text("Save")
            }
            TextButton(onClick = { editor.isPreview = !editor.isPreview }) {
                Text(if (editor.isPreview) "Edit" else "Preview")
            }
            if (editor.isSaving) {
                Text("Saving…")
            }
        }
        EditorMessages(editor, app)
        OutlinedTextField(
            value = editor.title,
            onValueChange = { editor.title = it },
            modifier = Modifier.fillMaxWidth().testTag(TITLE_FIELD),
            label = { Text("Title") },
            singleLine = true,
        )
        TagEditor(editor)
        val bodyModifier = Modifier.fillMaxWidth().weight(1f)
        if (editor.isPreview) {
            BodyPreview(editor, bodyModifier)
        } else {
            OutlinedTextField(
                value = editor.body,
                onValueChange = { editor.body = it },
                modifier = bodyModifier.testTag(BODY_FIELD),
                label = { Text("Note") },
            )
        }
    }
}

// The text keeps showing while its next form is parsed. It takes the focus that the text field
// had, as Ctrl+S only works with the focus inside the editor.
@Composable
private fun BodyPreview(
    editor: EditorViewModel,
    modifier: Modifier,
) {
    val body = editor.body
    val document by produceState<MarkdownDocument?>(null, body) { value = editor.parsed(body) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { focus.requestFocus() }
    Box(modifier.focusRequester(focus).focusable()) {
        document?.let { shown ->
            MarkdownPreview(shown, onToggle = { editor.toggleTask(shown, it) })
        }
    }
}

@Composable
private fun TagEditor(editor: EditorViewModel) {
    val scope = rememberCoroutineScope()
    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = editor.tagInput,
            onValueChange = { editor.tagInput = it },
            modifier = Modifier.testTag(TAG_FIELD),
            label = { Text("New tag") },
            singleLine = true,
        )
        TextButton(onClick = { scope.launch { editor.addTag() } }) { Text("Add tag") }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing)) {
            items(editor.tags.toList(), key = { it.toString() }) { tag ->
                InputChip(
                    selected = false,
                    onClick = { scope.launch { editor.removeTag(tag) } },
                    label = { Text("$tag ×") },
                )
            }
        }
    }
}

@Composable
private fun EditorMessages(
    editor: EditorViewModel,
    app: AppViewModel,
) {
    val scope = rememberCoroutineScope()
    val problem = editor.problem
    if (problem != null) {
        val deleted = problem == EditorProblem.DELETED
        Message(if (deleted) DELETED_MESSAGE else STALE_MESSAGE) {
            TextButton(onClick = { scope.launch { editor.saveAsNewNote() } }) {
                Text("Save as new note")
            }
            if (deleted) {
                TextButton(onClick = app::discardEditor) { Text("Discard") }
            } else {
                TextButton(onClick = { scope.launch { editor.reload() } }) {
                    Text("Discard and load theirs")
                }
            }
        }
    }
    editor.message?.let { Message(it) {} }
    editor.base?.let { app.attention.byNote[it.id] }?.let { attention ->
        Message("This note needs attention: ${attention.label}.") {
            TextButton(onClick = { app.openReview(attention.noteId) }) { Text("Review") }
        }
    }
}
