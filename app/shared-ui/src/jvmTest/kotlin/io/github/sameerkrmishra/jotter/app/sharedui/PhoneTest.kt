@file:OptIn(ExperimentalTestApi::class)

package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.sameerkrmishra.jotter.core.sync.ConflictResolver
import kotlinx.coroutines.Dispatchers
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val TIMEOUT_MS = 10_000L
private const val FOLDERS_NOTE = "Folders cannot be added on this device."

/**
 * What a phone needs of the shared UI: Back from every screen, keeping the draft when the app goes
 * to the background, no folder form where folders cannot be used, and forms that fit a narrow
 * screen.
 */
@Suppress("InjectDispatcher") // The tests stand in for a composition root.
class PhoneTest {
    private val device = SyncedDevice("phone")

    @AfterTest
    fun cleanUp() {
        device.close()
    }

    private fun app(
        width: Int? = null,
        block: ComposeUiTest.(AppViewModel) -> Unit,
    ) = runComposeUiTest {
        device.start()
        var app: AppViewModel? = null
        setContent {
            val scope = rememberCoroutineScope()
            val model =
                remember {
                    AppViewModel(
                        device.store.notes,
                        ConflictResolver(device.store),
                        scope,
                        Dispatchers.IO,
                        SyncControls(
                            device.manager,
                            device.scheduler,
                            foldersUnavailable = FOLDERS_NOTE,
                        ),
                    )
                }
            app = model
            Box(if (width == null) Modifier else Modifier.width(width.dp)) { JotterApp(model) }
        }
        waitForIdle()
        block(checkNotNull(app))
    }

    private fun ComposeUiTest.awaitText(text: String) =
        waitUntil(timeoutMillis = TIMEOUT_MS) {
            onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }

    private fun ComposeUiTest.awaitTag(tag: String) =
        waitUntil(timeoutMillis = TIMEOUT_MS) {
            onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }

    private fun ComposeUiTest.back(app: AppViewModel): Boolean =
        runOnUiThread { app.back() }.also { waitForIdle() }

    @Test
    fun backLeavesEachScreenAndSavesTheEditor() =
        app { app ->
            assertFalse(back(app), "the note list leaves the app")

            onNodeWithText("New note").performClick()
            onNodeWithTag(BODY_FIELD).performTextInput("milk")
            assertTrue(back(app))
            awaitText("milk")
            assertEquals(
                "milk",
                device.store.notes
                    .list()
                    .single()
                    .body,
            )

            onNodeWithText(BACKENDS_TITLE).performClick()
            awaitText(NO_BACKENDS_NOR_FOLDERS)
            assertTrue(back(app))
            awaitText("New note")

            val note =
                device.store.notes
                    .list()
                    .single()
            runOnUiThread { app.openReview(note.id) }
            awaitText("Back")
            // Going to the background with the review open keeps it, and it stays open.
            val kept = runOnUiThread { app.keep() }
            waitUntil(timeoutMillis = TIMEOUT_MS) { kept.isCompleted }
            awaitText("Back")
            assertTrue(back(app))
            awaitText("New note")
            assertFalse(back(app))
        }

    @Test
    fun keepSavesTheDraftAndLeavesTheEditorOpen() =
        app { app ->
            onNodeWithText("New note").performClick()
            onNodeWithTag(BODY_FIELD).performTextInput("bread")
            val job = runOnUiThread { app.keep() }
            waitUntil(timeoutMillis = TIMEOUT_MS) { job.isCompleted }
            assertEquals(
                "bread",
                device.store.notes
                    .list()
                    .single()
                    .body,
            )
            // Still in the editor, which goes on from the saved note.
            onNodeWithTag(BODY_FIELD).performTextInput(" rolls")
            assertTrue(back(app))
            waitUntil(timeoutMillis = TIMEOUT_MS) {
                device.store.notes
                    .list()
                    .single()
                    .body
                    .contains("rolls")
            }
            assertEquals(
                1,
                device.store.notes
                    .list()
                    .size,
            )
            // On the note list, there is nothing to keep.
            val idle = runOnUiThread { app.keep() }
            waitUntil(timeoutMillis = TIMEOUT_MS) { idle.isCompleted }
        }

    @Test
    fun aDeviceWithoutFoldersSaysSoAndOffersNoFolderForm() =
        app { _ ->
            onNodeWithText(BACKENDS_TITLE).performClick()
            awaitText(FOLDERS_NOTE)
            assertTrue(onAllNodesWithTag(FOLDER_FIELD).fetchSemanticsNodes().isEmpty())
            assertTrue(onAllNodesWithText(NO_BACKENDS).fetchSemanticsNodes().isEmpty())
        }

    @Test
    fun onANarrowScreenTheFieldsOfAFormGoOneUnderAnother() =
        app(width = 360) { _ ->
            onNodeWithText(BACKENDS_TITLE).performClick()
            awaitTag(SERVER_URL_FIELD)
            val url = onNodeWithTag(SERVER_URL_FIELD).getBoundsInRoot()
            val user = onNodeWithTag(SERVER_USER_FIELD).getBoundsInRoot()
            val address = onNodeWithTag(HUB_ADDRESS_FIELD).getBoundsInRoot()
            val token = onNodeWithTag(HUB_TOKEN_FIELD).getBoundsInRoot()
            assertTrue(user.top >= url.bottom, "user below URL: $url, $user")
            assertEquals(url.right - url.left, user.right - user.left)
            assertTrue(token.top >= address.bottom, "token below address: $address, $token")
        }

    @Test
    fun onAWideScreenTheyStaySideBySide() =
        app(width = 1000) { _ ->
            onNodeWithText(BACKENDS_TITLE).performClick()
            awaitTag(SERVER_URL_FIELD)
            val url = onNodeWithTag(SERVER_URL_FIELD).getBoundsInRoot()
            val user = onNodeWithTag(SERVER_USER_FIELD).getBoundsInRoot()
            assertEquals(url.top, user.top)
            assertTrue(user.left >= url.right)
        }
}
