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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.sameerkrmishra.jotter.core.storage.StorageBusyException
import kotlinx.coroutines.launch
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
        val graph = (application as JotterApplication).graph
        val share = shareOf(intent)
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST") // The only model this factory makes.
                return ShareViewModel(graph, share) as T
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

/** Where a share stands. */
internal sealed interface ShareState {
    /** The note is being stored. */
    data object Saving : ShareState

    /** It is stored; [leftOut] says what of the share was not taken, or is null. */
    data class Saved(
        val leftOut: String?,
    ) : ShareState

    /** Nothing was stored, and [reason] says why. */
    data class Refused(
        val reason: String,
    ) : ShareState
}

/**
 * Stores one share as a new note, once: it lives as long as the share window, across turning the
 * phone. The write runs on the graph's IO dispatcher, never the main thread.
 */
internal class ShareViewModel(
    graph: AppGraph,
    share: Share,
) : ViewModel() {
    /** Where the share stands. */
    var state by mutableStateOf<ShareState>(ShareState.Saving)
        private set

    init {
        when (val outcome = outcomeOf(share)) {
            is ShareOutcome.Refused -> state = ShareState.Refused(outcome.reason)
            is ShareOutcome.NewNote ->
                when (val opened = graph.opened) {
                    is Opened.Failed -> state = ShareState.Refused(opened.reason)
                    is Opened.Ready -> viewModelScope.launch { state = save(opened, outcome) }
                }
        }
    }

    @Suppress("TooGenericExceptionCaught") // The driver's exceptions are not in NoteStore's API.
    private suspend fun save(
        opened: Opened.Ready,
        note: ShareOutcome.NewNote,
    ): ShareState =
        try {
            withContext(opened.io) { opened.store.notes.create(note.title, note.body) }
            opened.sync.soon()
            ShareState.Saved(note.leftOut)
        } catch (_: StorageBusyException) {
            ShareState.Refused("The notes are busy. Share the text again in a moment.")
        } catch (e: RuntimeException) {
            ShareState.Refused("The note could not be saved: ${e.message}")
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
