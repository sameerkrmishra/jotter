package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch

internal const val TITLE_FIELD = "title"
internal const val BODY_FIELD = "body"
internal const val STALE_MESSAGE =
    "This note was changed elsewhere. Your text is kept here and has not been saved."
internal const val DELETED_MESSAGE =
    "This note was deleted forever. Your text is kept here and has not been saved."

/** The editor: plain text for now. Back saves, and stays here if the text cannot be saved. */
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
        OutlinedTextField(
            value = editor.body,
            onValueChange = { editor.body = it },
            modifier = Modifier.fillMaxWidth().weight(1f).testTag(BODY_FIELD),
            label = { Text("Note") },
        )
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
}
