package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * The whole app, for a window or an Activity to show: the note grid, or the editor while a note is
 * open. Its state lives in [viewModel].
 */
@Composable
public fun JotterApp(viewModel: AppViewModel) {
    RefreshWhenFocused(LocalWindowInfo.current.isWindowFocused, viewModel::refresh)
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            val editor = viewModel.editor
            if (editor == null) {
                NotesScreen(viewModel)
            } else {
                EditorScreen(editor, viewModel)
            }
            if (viewModel.pendingExit != null) {
                ConfirmExit(viewModel)
            }
        }
    }
}

/**
 * What a window shows instead of [JotterApp] when the notes could not be opened. An app started
 * from a menu has no terminal, so this is the only place the user can see why.
 *
 * @param reason What went wrong.
 * @param onClose Closes the app.
 */
@Composable
public fun CannotOpenNotes(
    reason: String,
    onClose: () -> Unit,
) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(spacing),
                verticalArrangement = Arrangement.spacedBy(spacing),
            ) {
                Text("Jotter cannot open your notes", style = MaterialTheme.typography.titleLarge)
                Text(reason)
                Button(onClick = onClose) { Text("Close") }
            }
        }
    }
}

@Composable
private fun ConfirmExit(app: AppViewModel) {
    AlertDialog(
        onDismissRequest = app::keepEditing,
        title = { Text("Your text could not be saved") },
        text = { Text("If you close now, what is in the editor is lost.") },
        confirmButton = { TextButton(onClick = app::keepEditing) { Text("Keep editing") } },
        dismissButton = {
            TextButton(onClick = app::exitWithoutSaving) { Text("Close without saving") }
        },
    )
}

/** Calls [refresh] each time [focused] becomes true: another process may have changed the notes. */
@Composable
internal fun RefreshWhenFocused(
    focused: Boolean,
    refresh: () -> Unit,
) {
    LaunchedEffect(focused) {
        if (focused) refresh()
    }
}
