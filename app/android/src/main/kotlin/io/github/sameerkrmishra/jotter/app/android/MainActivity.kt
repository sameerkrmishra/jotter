package io.github.sameerkrmishra.jotter.app.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import io.github.sameerkrmishra.jotter.app.sharedui.CannotOpenNotes
import io.github.sameerkrmishra.jotter.app.sharedui.JotterApp

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
        when (val opened = graph.opened) {
            is Opened.Failed ->
                setContent {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        CannotOpenNotes(opened.reason, ::finish)
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
