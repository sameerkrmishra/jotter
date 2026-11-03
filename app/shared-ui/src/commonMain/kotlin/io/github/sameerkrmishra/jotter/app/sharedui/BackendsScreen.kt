package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.sameerkrmishra.jotter.app.sync.Backend

internal const val FOLDER_FIELD = "folder"
internal const val BACKENDS_TITLE = "Sync"
internal const val NO_BACKENDS =
    "This device syncs with no backend yet. Add a folder, such as a USB stick or a folder " +
        "that another program keeps in step with other computers."

/** The test tag of the card of the backend [id]. */
internal fun backendTag(id: String) = "backend-$id"

/** The backend screen: see [BackendsViewModel]. */
@Composable
internal fun BackendsScreen(
    backends: BackendsViewModel,
    onClose: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(spacing),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("Back") }
            Text(BACKENDS_TITLE, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            Button(onClick = backends::syncNow, enabled = !backends.syncing) {
                Text(if (backends.syncing) "Syncing…" else "Sync now")
            }
        }
        backends.problem?.let { Message(it) {} }
        backends.message?.let {
            Message(it) { TextButton(onClick = backends::dismissMessage) { Text("Dismiss") } }
        }
        AddFolder(backends)
        if (backends.backends.isEmpty()) Text(NO_BACKENDS)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(spacing)) {
            items(backends.backends, key = { it.id }) { backend ->
                BackendCard(
                    backend,
                    backends.statusOf(backend),
                    onRemove = { backends.askToRemove(backend) },
                )
            }
        }
    }
    backends.settingUp?.let { path ->
        AlertDialog(
            onDismissRequest = backends::cancelSetUp,
            title = { Text(SET_UP_TITLE) },
            text = { Text(setUpExplanation(path)) },
            confirmButton = {
                TextButton(
                    onClick = backends::confirmSetUp,
                ) { Text("Set up and add") }
            },
            dismissButton = { TextButton(onClick = backends::cancelSetUp) { Text("Cancel") } },
        )
    }
    backends.removing?.let { backend ->
        AlertDialog(
            onDismissRequest = backends::cancelRemove,
            title = { Text(REMOVE_TITLE) },
            text = { Text(removeExplanation(backend)) },
            confirmButton = { TextButton(onClick = backends::confirmRemove) { Text("Remove") } },
            dismissButton = { TextButton(onClick = backends::cancelRemove) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AddFolder(backends: BackendsViewModel) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = backends.folder,
            onValueChange = backends::editFolder,
            modifier = Modifier.weight(1f).testTag(FOLDER_FIELD),
            label = { Text("Folder, such as /run/media/you/STICK") },
            singleLine = true,
        )
        Button(
            onClick = backends::addFolder,
            enabled = !backends.busy && backends.folder.isNotBlank(),
        ) { Text("Add folder") }
    }
}

@Composable
private fun BackendCard(
    backend: Backend,
    lines: BackendLines,
    onRemove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().testTag(backendTag(backend.id))) {
        Column(Modifier.padding(spacing), verticalArrangement = Arrangement.spacedBy(spacing)) {
            Text(backend.name, style = MaterialTheme.typography.titleMedium)
            Text(backend.config.location)
            Text(lines.reachability)
            Text(lines.lastSuccess)
            lines.lastProblem?.let { Text(it) }
            lines.issues.forEach { Text("• $it") }
            TextButton(onClick = onRemove) { Text("Remove") }
        }
    }
}
