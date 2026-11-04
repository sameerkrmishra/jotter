package io.github.sameerkrmishra.jotter.app.android

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import io.github.sameerkrmishra.jotter.app.sharedui.CannotOpenNotes
import io.github.sameerkrmishra.jotter.app.sharedui.DebugLogExport
import io.github.sameerkrmishra.jotter.app.sharedui.JotterApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The app: the shared screens of `app:shared-ui` (ADR-0010) on the process's [AppGraph]. Their
 * state lives in the graph, not here, so turning the phone keeps the open note and its draft.
 *
 * Back closes the screen on top, as the screens' own Back buttons do, and on the note list leaves
 * the app as any app does. Coming to the screen syncs; leaving it stores the draft and asks
 * WorkManager for a sync, as Android may stop the process at any time after.
 */
public class MainActivity : ComponentActivity() {
    private val graph: AppGraph get() = (application as JotterApplication).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 15 and later draw every app under the system bars; the padding below keeps the
        // screens out from under them and the keyboard.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        graph.debugExport.screen = documents
        when (val opened = graph.opened) {
            is Opened.Failed -> {
                val debugLog = graph.debugLog()
                setContent {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        CannotOpenNotes(opened.reason, debugLog, ::finish)
                    }
                }
            }
            is Opened.Ready -> {
                onBackPressedDispatcher.addCallback(this, BackToList(opened))
                setContent {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) { JotterApp(opened.app) }
                }
            }
        }
    }

    override fun onDestroy() {
        if (graph.debugExport.screen === documents) graph.debugExport.screen = null
        // A picker still open answers this Activity no more: its export ends as cancelled.
        documents.cancel()
        super.onDestroy()
    }

    /**
     * Saves the debug log where the user chooses, through Android's document picker (the Storage
     * Access Framework): any folder, or a cloud drive's, that offers to take a file.
     */
    private val documents =
        object : DebugLogExport {
            private var waiting: CompletableDeferred<Uri?>? = null

            private val picker =
                registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
                    waiting?.complete(it)
                }

            override suspend fun export(text: String): String? {
                val answer = CompletableDeferred<Uri?>()
                waiting = answer
                picker.launch(DEBUG_LOG_FILE_NAME)
                val uri = answer.await() ?: return null
                withContext(Dispatchers.IO) {
                    checkNotNull(contentResolver.openOutputStream(uri, "wt")) { "No stream" }.use {
                        it.write(text.encodeToByteArray())
                    }
                }
                return "Saved the debug log. Attach that file to your issue."
            }

            fun cancel() {
                waiting?.complete(null)
            }
        }

    override fun onStart() {
        super.onStart()
        graph.opened.sync?.foreground()
    }

    override fun onStop() {
        (graph.opened as? Opened.Ready)?.let { opened ->
            opened.app.keep()
            opened.sync.soon()
        }
        super.onStop()
    }

    /** Back on a screen over the note list goes to that list; on the list, Android's own Back. */
    private inner class BackToList(
        private val opened: Opened.Ready,
    ) : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (!opened.app.back()) {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }
    }
}
