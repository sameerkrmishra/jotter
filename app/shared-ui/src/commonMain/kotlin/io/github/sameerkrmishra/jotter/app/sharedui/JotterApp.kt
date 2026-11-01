package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
        }
    }
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
