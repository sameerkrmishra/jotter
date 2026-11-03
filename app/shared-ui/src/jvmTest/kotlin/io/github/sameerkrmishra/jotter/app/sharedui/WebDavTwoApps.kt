@file:OptIn(ExperimentalTestApi::class)

package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.sameerkrmishra.jotter.app.sync.SecretKind
import io.github.sameerkrmishra.jotter.core.sync.ConflictResolver
import kotlinx.coroutines.Dispatchers
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TIMEOUT_MS = 30_000L
private const val LAPTOP = "laptop"
private const val DESKTOP = "desktop"

/**
 * Two apps side by side, each with a database of its own and the desktop app's real sync stack,
 * syncing through the WebDAV collection at [url]. What the user does, they do on the screen: add
 * the server with a wrong password and read why it was refused, add it with the right one and set
 * it up on one app, add it on the other, and see a note arrive.
 *
 * `TwoAppsWebDavTest` runs it in `check` against a small server in the test process; the `webdav`
 * CI job runs it against lighttpd in a container (`live.WebDavAppsLiveTest`).
 */
@Suppress("InjectDispatcher") // The test stands in for two composition roots.
internal class WebDavTwoApps(
    private val url: String,
    private val user: String,
    private val password: String,
) {
    fun run() {
        SyncedDevice(LAPTOP).use { laptop ->
            SyncedDevice(DESKTOP).use { desktop ->
                runComposeUiTest {
                    laptop.start()
                    desktop.start()
                    setContent {
                        Row(Modifier.fillMaxSize()) {
                            App(LAPTOP, laptop, Modifier.weight(1f))
                            App(DESKTOP, desktop, Modifier.weight(1f))
                        }
                    }
                    steps(laptop, desktop)
                }
            }
        }
    }

    @Composable
    private fun App(
        tag: String,
        device: SyncedDevice,
        modifier: Modifier,
    ) {
        Box(modifier.testTag(tag)) {
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
    }

    private fun ComposeUiTest.steps(
        laptop: SyncedDevice,
        desktop: SyncedDevice,
    ) {
        click(LAPTOP, BACKENDS_TITLE)
        addServer(LAPTOP, "not-the-password")
        await(LAPTOP, "The server refused the user name or app password", substring = true)
        click(LAPTOP, "Dismiss")

        addServer(LAPTOP, password)
        // A dialog is a popup, outside the app's own layout.
        waitUntil(timeoutMillis = TIMEOUT_MS) {
            onAllNodes(hasText(SET_UP_SERVER_TITLE)).fetchSemanticsNodes().isNotEmpty()
        }
        onNode(hasText("Set up and add") and hasAnyAncestor(isDialog())).performClick()
        await(LAPTOP, "It syncs now.", substring = true)
        await(LAPTOP, "Its password is kept in the keyring.")
        val laptopBackend =
            laptop.store.backends
                .list()
                .single()
        assertTrue(!laptopBackend.settings.contains(password), laptopBackend.settings)
        assertEquals(
            password,
            laptop.secrets.keyring.secrets.entries
                .single { it.key.kind == SecretKind.APP_PASSWORD }
                .value
                .reveal(),
        )

        click(DESKTOP, BACKENDS_TITLE)
        addServer(DESKTOP, password)
        await(DESKTOP, "It syncs now.", substring = true)

        val note = laptop.store.notes.create("", "Bought milk, through WebDAV")
        // The scheduler syncs it a moment after the edit.
        waitUntil(timeoutMillis = TIMEOUT_MS) { pushed(laptop) }
        click(DESKTOP, "Sync now")
        waitUntil(timeoutMillis = TIMEOUT_MS) { desktop.store.notes.get(note.id) != null }
        assertEquals(
            "Bought milk, through WebDAV",
            desktop.store.notes
                .get(note.id)
                ?.body,
        )
    }

    /** Whether [device]'s last sync sent something, so its note is on the server. */
    private fun pushed(device: SyncedDevice): Boolean =
        device.store.backends
            .list()
            .single()
            .lastAttempt
            ?.detail
            ?.contains("sent 1 file") == true

    private fun ComposeUiTest.addServer(
        app: String,
        password: String,
    ) {
        node(app, hasTestTag(SERVER_URL_FIELD)).performTextReplacement(url)
        node(app, hasTestTag(SERVER_USER_FIELD)).performTextReplacement(user)
        node(app, hasTestTag(SERVER_PASSWORD_FIELD)).performTextReplacement(password)
        click(app, ADD_SERVER)
    }

    private fun inApp(app: String) = hasAnyAncestor(hasTestTag(app))

    private fun ComposeUiTest.node(
        app: String,
        matcher: SemanticsMatcher,
    ) = onNode(matcher and inApp(app))

    private fun ComposeUiTest.click(
        app: String,
        text: String,
    ) = node(app, hasText(text)).performClick()

    private fun ComposeUiTest.await(
        app: String,
        text: String,
        substring: Boolean = false,
    ) = waitUntil(timeoutMillis = TIMEOUT_MS) {
        onAllNodes(hasText(text, substring = substring) and inApp(app))
            .fetchSemanticsNodes()
            .isNotEmpty()
    }
}
