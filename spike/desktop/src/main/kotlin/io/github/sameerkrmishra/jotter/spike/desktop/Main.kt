package io.github.sameerkrmishra.jotter.spike.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.delay

private const val AUTOCLOSE_ARG = "--autoclose"
private const val AUTOCLOSE_ENV = "JOTTER_SPIKE_AUTOCLOSE"
private const val AUTOCLOSE_DELAY_MS = 4_000L

/** Starts the spike window. With `--autoclose` it exits by itself after a few seconds. */
public fun main(args: Array<String>): Unit =
    application {
        Window(onCloseRequest = ::exitApplication, title = "Jotter spike") {
            if (AUTOCLOSE_ARG in args || System.getenv(AUTOCLOSE_ENV) != null) {
                LaunchedEffect(Unit) {
                    delay(AUTOCLOSE_DELAY_MS)
                    println("jotter-spike: window shown, closing")
                    exitApplication()
                }
            }
            MaterialTheme { Surface { NotesScreen() } }
        }
    }

@Composable
private fun NotesScreen() {
    var draft by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf(listOf("Buy milk", "Grüße — ünïcödé ✓")) }
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Note") },
            )
            Button(onClick = {
                notes = addNote(notes, draft)
                draft = ""
            }) { Text("Add") }
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(notes) { Text(it, Modifier.padding(vertical = 4.dp)) }
        }
    }
}

/** Returns [notes] with [draft] prepended, or [notes] unchanged when [draft] is blank. */
internal fun addNote(
    notes: List<String>,
    draft: String,
): List<String> = if (draft.isBlank()) notes else listOf(draft.trim()) + notes
