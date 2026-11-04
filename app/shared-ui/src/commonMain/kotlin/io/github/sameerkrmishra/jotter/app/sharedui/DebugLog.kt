package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import io.github.sameerkrmishra.jotter.core.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal const val DEBUG_LOG_TITLE = "Debug log"
internal const val DEBUG_LOG_TEXT = "debug-log-text"
internal const val DEBUG_LOG_LOADING = "Putting the debug log together…"
internal const val DEBUG_LOG_FAILED =
    "The debug log could not be put together. If it keeps happening, report it without the log."
internal const val DEBUG_LOG_NOT_SAVED =
    "The debug log could not be saved there. Choose another place, with enough free space."

/**
 * Saves or shares a debug log that the user has read. Each app has its own: the desktop asks for a
 * file, Android for a place through its document picker. Nothing is ever uploaded by Jotter.
 */
public fun interface DebugLogExport {
    /**
     * Saves or shares [text]. Returns what to tell the user, such as where it went, or null if
     * they cancelled.
     */
    public suspend fun export(text: String): String?
}

/**
 * What the debug log screen needs from the app.
 *
 * @property report Builds the debug log, as `DebugReport` does.
 * @property export Saves or shares it.
 * @property exportLabel The export button, such as "Save to a file…".
 */
public class DebugLogControls(
    public val report: suspend () -> String,
    public val export: DebugLogExport,
    public val exportLabel: String,
)

/**
 * The debug log screen: the whole debug log, as it would be saved, and a button that saves or
 * shares it (ADR-0021). The user sees all of it before it goes anywhere.
 */
@Stable
public class DebugLogViewModel internal constructor(
    private val controls: DebugLogControls,
    private val scope: CoroutineScope,
) {
    /** The debug log, or null while it is put together. */
    internal var text by mutableStateOf<String?>(null)
        private set

    /** What became of the last export, or why the log is missing. */
    internal var message by mutableStateOf<String?>(null)
        private set

    /** Whether an export runs. */
    internal var isExporting by mutableStateOf(false)
        private set

    internal val exportLabel: String get() = controls.exportLabel

    private val loading: Job = scope.launch { text = loaded() }

    @Suppress("TooGenericExceptionCaught") // A debug log is wanted most when things fail.
    private suspend fun loaded(): String? =
        try {
            controls.report()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(e) { "The debug log could not be built" }
            message = DEBUG_LOG_FAILED
            null
        }

    /** Saves or shares the debug log as it is shown. */
    @Suppress("TooGenericExceptionCaught") // The export's own failures are told the same way.
    internal fun export() {
        val shown = text ?: return
        if (isExporting) return
        isExporting = true
        scope.launch {
            message =
                try {
                    controls.export.export(shown)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e) { "The debug log could not be exported" }
                    DEBUG_LOG_NOT_SAVED
                } finally {
                    isExporting = false
                }
        }
    }

    /** Stops putting the log together, if it still is. */
    internal fun close() {
        loading.cancel()
    }

    private companion object {
        val log = Logger("ui")
    }
}

/** The debug log screen: see [DebugLogViewModel]. */
@Composable
internal fun DebugLogScreen(
    debugLog: DebugLogViewModel,
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
            Text(DEBUG_LOG_TITLE, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            Button(
                onClick = debugLog::export,
                enabled = debugLog.text != null && !debugLog.isExporting,
            ) {
                Text(debugLog.exportLabel)
            }
        }
        debugLog.message?.let { Text(it) }
        val text = debugLog.text
        if (text == null) {
            if (debugLog.message == null) Text(DEBUG_LOG_LOADING)
        } else {
            OutlinedCard(Modifier.fillMaxWidth().weight(1f)) {
                SelectionContainer {
                    Text(
                        text,
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(spacing)
                            .testTag(DEBUG_LOG_TEXT),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
