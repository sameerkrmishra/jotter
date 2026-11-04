package io.github.sameerkrmishra.jotter.app.android

import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
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
import io.github.sameerkrmishra.jotter.app.sharedui.DatabaseExportPlace
import io.github.sameerkrmishra.jotter.app.sharedui.DebugLogExport
import io.github.sameerkrmishra.jotter.app.sharedui.ExportStep
import io.github.sameerkrmishra.jotter.app.sharedui.JotterApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

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
        graph.databaseExport.screen = databaseDocument
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
        if (graph.databaseExport.screen === databaseDocument) graph.databaseExport.screen = null
        // A picker still open answers this Activity no more: its export ends as cancelled.
        documents.cancel()
        databaseDocument.cancel()
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

            @Suppress("InjectDispatcher") // The Activity is platform glue, like the graph.
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

    /**
     * Exports the database where the user chooses through Android's document picker. The picker
     * asks before it overwrites a document, or gives the new one another name.
     */
    private val databaseDocument =
        object : DatabaseExportPlace {
            private var waiting: CompletableDeferred<Uri?>? = null

            private val picker =
                registerForActivityResult(
                    ActivityResultContracts.CreateDocument(DATABASE_FILE_TYPE),
                ) { waiting?.complete(it) }

            @Suppress("InjectDispatcher") // The Activity is platform glue, like the graph.
            override suspend fun export(): ExportStep {
                val opened = graph.opened as? Opened.Ready ?: return ExportStep.Cancelled
                return exportThroughPicker(
                    opened.export::export,
                    graph.exportStaging,
                    Dispatchers.IO,
                ) {
                    val answer = CompletableDeferred<Uri?>()
                    waiting = answer
                    picker.launch(DATABASE_FILE_NAME)
                    answer.await()?.let(::document)
                }
            }

            fun cancel() {
                waiting?.complete(null)
            }
        }

    /** The document at [uri], which the picker gave. */
    private fun document(uri: Uri): ChosenDocument {
        val resolver = applicationContext.contentResolver
        val name =
            resolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
                ?: DATABASE_FILE_NAME
        return object : ChosenDocument {
            override val name: String = name

            override fun open(): OutputStream =
                checkNotNull(resolver.openOutputStream(uri, "wt")) { "No stream for the document" }

            override fun delete() {
                DocumentsContract.deleteDocument(resolver, uri)
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
