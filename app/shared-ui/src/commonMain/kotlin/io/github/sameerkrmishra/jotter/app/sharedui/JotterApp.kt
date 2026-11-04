package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * The whole app, for a window or an Activity to show: the note grid, the editor while a note is
 * open, or the review of a note's sync problems. Its state lives in [viewModel].
 */
@Composable
public fun JotterApp(viewModel: AppViewModel) {
    RefreshWhenFocused(LocalWindowInfo.current.isWindowFocused, viewModel::refresh)
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            val editor = viewModel.editor
            val review = viewModel.review
            val backends = viewModel.backends
            val debugLog = viewModel.debugLogView
            when {
                debugLog != null -> DebugLogScreen(debugLog, viewModel::closeDebugLog)
                review != null -> ReviewScreen(review, viewModel::closeReview)
                backends != null ->
                    BackendsScreen(
                        backends,
                        viewModel::closeBackends,
                        viewModel::openDebugLog.takeIf { viewModel.hasDebugLog },
                    )
                editor != null -> EditorScreen(editor, viewModel)
                else -> NotesScreen(viewModel)
            }
            if (viewModel.pendingExit != null) {
                ConfirmExit(viewModel)
            }
        }
    }
}

/**
 * What a window shows instead of [JotterApp] when the notes could not be opened. An app started
 * from a menu has no terminal, so this is the only place the user can see why. It changes nothing:
 * the file stays as it is, and the app never makes a new one in its place.
 *
 * @param reason What went wrong, and what to do, in plain words.
 * @param debugLog The debug log, to attach to a report, or null where there is none.
 * @param onClose Closes the app.
 */
@Composable
public fun CannotOpenNotes(
    reason: String,
    debugLog: DebugLogControls? = null,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf<DebugLogViewModel?>(null) }
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            val open = shown
            if (open != null) {
                DebugLogScreen(open) {
                    open.close()
                    shown = null
                }
            } else {
                Column(
                    Modifier.fillMaxSize().padding(spacing),
                    verticalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    Text(CANNOT_OPEN_TITLE, style = MaterialTheme.typography.titleLarge)
                    Text(reason)
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                        Button(onClick = onClose) { Text("Close") }
                        if (debugLog != null) {
                            TextButton(onClick = { shown = DebugLogViewModel(debugLog, scope) }) {
                                Text(SHOW_DEBUG_LOG)
                            }
                        }
                    }
                }
            }
        }
    }
}

internal const val CANNOT_OPEN_TITLE = "Jotter cannot open your notes"
internal const val SHOW_DEBUG_LOG = "Show debug log"

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
