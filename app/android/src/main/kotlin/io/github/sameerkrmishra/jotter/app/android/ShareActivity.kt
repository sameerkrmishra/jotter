package io.github.sameerkrmishra.jotter.app.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DEFAULT_ARGS_KEY
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import kotlinx.coroutines.withContext

/** The test tag of the share target's message. */
internal const val SHARE_MESSAGE = "share-message"

/** What the share target shows once the note is stored. */
internal const val SAVED = "Saved as a new note in Jotter."

/**
 * The "Share to" target (PLAN.md §1): text that another app shares becomes a new note, and the
 * user sees that it was saved, or why not. Files and images are refused in plain words, as notes
 * are text only; text too long for a note is refused whole. It is a small window over the sharing
 * app, and the note is stored once, also if the phone is turned meanwhile. Then WorkManager syncs
 * soon, as this process may be stopped right after.
 */
public class ShareActivity : ComponentActivity() {
    private val model: ShareViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras,
            ): T {
                val graph = (application as JotterApplication).graph
                val model =
                    ShareViewModel(
                        readShare { shareOf(intent) },
                        GraphNotes(graph),
                        // Without the intent's extras as defaults, which the handle would copy:
                        // they may not unparcel, and a long text would overflow the saved state.
                        MutableCreationExtras(extras)
                            .apply { set(DEFAULT_ARGS_KEY, Bundle()) }
                            .createSavedStateHandle(),
                    )
                @Suppress("UNCHECKED_CAST") // The only model this factory makes.
                return model as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShareScreen(model.state, onOpen = ::openJotter, onClose = ::finish)
        }
    }

    private fun openJotter() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }

    /** The notes of [graph], opened on first use, off the main thread. */
    private class GraphNotes(
        private val graph: AppGraph,
    ) : SharedNotes {
        override suspend fun create(
            title: String,
            body: String,
        ): String =
            when (val opened = graph.opened) {
                is Opened.Failed -> throw NotesUnavailableException(opened.reason)
                is Opened.Ready ->
                    withContext(opened.io) {
                        opened.store.notes
                            .create(title, body)
                            .id
                            .toString()
                    }
            }

        override fun syncSoon() {
            graph.opened.sync?.soon()
        }
    }

    private companion object {
        fun shareOf(intent: Intent): Share =
            Share(
                isSend = intent.action == Intent.ACTION_SEND,
                type = intent.type,
                text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT),
                subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT),
                hasStream = streamOf(intent) != null,
            )

        fun streamOf(intent: Intent): Uri? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION") // The typed variant is from API 33.
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
    }
}

/** The share window: what happened, and how to go on. */
@Composable
internal fun ShareScreen(
    state: ShareState,
    onOpen: () -> Unit,
    onClose: () -> Unit,
) {
    MaterialTheme {
        Surface {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Jotter", style = MaterialTheme.typography.titleLarge)
                val message =
                    when (state) {
                        ShareState.Saving -> "Saving…"
                        is ShareState.Saved -> listOfNotNull(SAVED, state.leftOut).joinToString(" ")
                        is ShareState.Refused -> state.reason
                    }
                Text(message, Modifier.testTag(SHARE_MESSAGE))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state is ShareState.Saved) {
                        TextButton(onClick = onOpen) { Text("Open Jotter") }
                        Button(onClick = onClose) { Text("Done") }
                    } else {
                        Button(onClick = onClose, enabled = state != ShareState.Saving) {
                            Text("Close")
                        }
                    }
                }
            }
        }
    }
}
