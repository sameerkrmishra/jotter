@file:OptIn(ExperimentalTestApi::class)

package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.sameerkrmishra.jotter.core.sync.ConflictResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TIMEOUT_MS = 10_000L
private const val LOG = "Jotter debug log\n\nApp: desktop 1.2.3\nBackends: 0\n"
private const val SAVE = "Save to a file…"

/** The debug log: shown in full, then saved or shared only when the user asks. */
@Suppress("InjectDispatcher") // The tests stand in for a composition root.
class DebugLogScreenTest {
    private val device = SyncedDevice("here")
    private val exported = mutableListOf<String>()

    @AfterTest
    fun close() {
        device.close()
    }

    private fun controls(
        report: suspend () -> String = { LOG },
        export: DebugLogExport =
            DebugLogExport {
                exported += it
                "Saved to /tmp/jotter-debug.txt."
            },
    ) = DebugLogControls(report, export, SAVE)

    private fun app(
        controls: DebugLogControls = controls(),
        block: ComposeUiTest.(AppViewModel) -> Unit,
    ) = runComposeUiTest {
        lateinit var app: AppViewModel
        setContent {
            val scope = rememberCoroutineScope()
            app =
                remember {
                    AppViewModel(
                        device.store.notes,
                        ConflictResolver(device.store),
                        scope,
                        Dispatchers.IO,
                        device.controls,
                        controls,
                    )
                }
            JotterApp(app)
        }
        waitForIdle()
        block(app)
    }

    private fun ComposeUiTest.awaitText(text: String) =
        waitUntil(timeoutMillis = TIMEOUT_MS) {
            onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }

    private fun ComposeUiTest.openDebugLog() {
        onNodeWithText(BACKENDS_TITLE).performClick()
        awaitText("Sync now")
        onNodeWithText(DEBUG_LOG_TITLE).performClick()
        awaitText("App: desktop 1.2.3")
    }

    @Test
    fun theWholeLogIsShownBeforeItIsSaved() =
        app {
            openDebugLog()
            onNodeWithTag(DEBUG_LOG_TEXT).assertExists()
            awaitText("Backends: 0")
            assertTrue(exported.isEmpty())
            onNodeWithText(SAVE).performClick()
            awaitText("Saved to /tmp/jotter-debug.txt.")
            assertEquals(listOf(LOG), exported)
        }

    @Test
    fun backLeavesTheDebugLog() =
        app { app ->
            openDebugLog()
            runOnIdle { assertTrue(app.back()) }
            awaitText("Sync now")
            onNodeWithText("Back").performClick()
            waitForIdle()
            assertEquals(null, app.debugLogView)
        }

    @Test
    fun aCancelledSaveSaysNothing() =
        app(controls(export = { null })) {
            openDebugLog()
            onNodeWithText(SAVE).performClick()
            waitForIdle()
            assertTrue(
                onAllNodesWithText("Saved", substring = true).fetchSemanticsNodes().isEmpty(),
            )
        }

    @Test
    fun aFailedSaveSaysWhatToDo() =
        app(controls(export = { throw IOException("No space left on device") })) {
            openDebugLog()
            onNodeWithText(SAVE).performClick()
            awaitText(DEBUG_LOG_NOT_SAVED)
            assertTrue(
                onAllNodesWithText("No space", substring = true).fetchSemanticsNodes().isEmpty(),
            )
        }

    @Test
    fun whileItIsBuiltNothingCanBeSaved() {
        val ready = CompletableDeferred<String>()
        app(controls(report = { ready.await() })) {
            onNodeWithText(BACKENDS_TITLE).performClick()
            awaitText("Sync now")
            onNodeWithText(DEBUG_LOG_TITLE).performClick()
            awaitText(DEBUG_LOG_LOADING)
            onNodeWithText(SAVE).assertIsNotEnabled()
            ready.complete(LOG)
            awaitText("App: desktop 1.2.3")
            onNodeWithText(SAVE).assertIsEnabled()
        }
    }

    @Test
    fun aLogThatCannotBeBuiltSaysSo() =
        app(controls(report = { error("broken") })) {
            onNodeWithText(BACKENDS_TITLE).performClick()
            awaitText("Sync now")
            onNodeWithText(DEBUG_LOG_TITLE).performClick()
            awaitText(DEBUG_LOG_FAILED)
            onNodeWithText(SAVE).assertIsNotEnabled()
        }

    @Test
    fun withoutADebugLogTheSyncScreenOffersNone() =
        runComposeUiTest {
            setContent {
                val scope = rememberCoroutineScope()
                JotterApp(
                    remember {
                        AppViewModel(
                            device.store.notes,
                            ConflictResolver(device.store),
                            scope,
                            Dispatchers.IO,
                            device.controls,
                        )
                    },
                )
            }
            onNodeWithText(BACKENDS_TITLE).performClick()
            awaitText("Sync now")
            assertTrue(onAllNodesWithText(DEBUG_LOG_TITLE).fetchSemanticsNodes().isEmpty())
        }

    @Test
    fun theDebugLogCanBeShownWhenTheNotesCannotBeOpened() =
        runComposeUiTest {
            var closed = false
            setContent { CannotOpenNotes("The disk is full.", controls()) { closed = true } }
            onNodeWithText(CANNOT_OPEN_TITLE).assertExists()
            onNodeWithText(SHOW_DEBUG_LOG).performClick()
            awaitText("App: desktop 1.2.3")
            onNodeWithText(SAVE).performClick()
            awaitText("Saved to")
            onNodeWithText("Back").performClick()
            awaitText("The disk is full.")
            onNodeWithText("Close").performClick()
            waitForIdle()
            assertTrue(closed)
            assertEquals(listOf(LOG), exported)
        }
}
