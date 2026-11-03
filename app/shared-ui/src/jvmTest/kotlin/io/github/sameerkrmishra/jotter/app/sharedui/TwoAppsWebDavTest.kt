@file:OptIn(ExperimentalTestApi::class)

package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.sameerkrmishra.jotter.app.sync.Secret
import io.github.sameerkrmishra.jotter.app.sync.SecretKey
import io.github.sameerkrmishra.jotter.app.sync.SecretStore
import io.github.sameerkrmishra.jotter.app.sync.SecretStoreUnavailableException
import io.github.sameerkrmishra.jotter.app.sync.SecretStores
import io.github.sameerkrmishra.jotter.core.sync.ConflictResolver
import kotlinx.coroutines.Dispatchers
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TIMEOUT_MS = 20_000L

/** WebDAV on the backend screen, against [TinyDavServer] in this process. */
@Suppress("InjectDispatcher") // The tests stand in for a composition root.
class TwoAppsWebDavTest {
    private val server = TinyDavServer()

    @AfterTest
    fun cleanUp() {
        server.close()
    }

    @Test
    fun twoAppsSyncThroughAServer() {
        WebDavTwoApps(server.url, "me", server.password).run()
    }

    private fun screen(
        device: SyncedDevice,
        block: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        device.start()
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
        block()
    }

    private fun ComposeUiTest.awaitText(
        text: String,
        substring: Boolean = true,
    ) = waitUntil(timeoutMillis = TIMEOUT_MS) {
        onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    private fun ComposeUiTest.addServer(url: String = server.url) {
        onNodeWithTag(SERVER_URL_FIELD).performTextReplacement(url)
        onNodeWithTag(SERVER_USER_FIELD).performTextReplacement("me")
        onNodeWithTag(SERVER_PASSWORD_FIELD).performTextReplacement(server.password)
        onNodeWithText(ADD_SERVER).performClick()
    }

    private fun ComposeUiTest.clickInDialog(text: String) =
        onNode(hasText(text) and hasAnyAncestor(isDialog())).performClick()

    @Test
    fun aRefusedPasswordAsksForANewOne() =
        SyncedDevice("here").use { device ->
            screen(device) {
                addServer()
                awaitText(SET_UP_SERVER_TITLE)
                clickInDialog("Set up and add")
                awaitText("It syncs now.")
                awaitText("Last synced just now.")

                server.password = "a-new-one"
                onNodeWithText("Sync now").performClick()
                awaitText("The server refused the user name or app password")
                onNodeWithText(NEW_PASSWORD).performClick()
                awaitText(PASSWORD_TITLE)
                onNodeWithTag(NEW_PASSWORD_FIELD).performTextReplacement("still-wrong")
                clickInDialog("Check and keep")
                awaitText("The server refused this app password too.")
                onNodeWithTag(NEW_PASSWORD_FIELD).performTextReplacement("a-new-one")
                clickInDialog("Check and keep")
                awaitText("Stored the new app password")
                waitUntil(timeoutMillis = TIMEOUT_MS) {
                    device.store.backends
                        .list()
                        .single()
                        .lastAttempt
                        ?.outcome == "SYNCED"
                }
                onNodeWithText(NEW_PASSWORD).performClick()
                clickInDialog("Cancel")
            }
        }

    @Test
    fun withoutAKeyringThePasswordMayGoInTheFile() {
        val secrets = TestSecrets()
        val noKeyring =
            object : SecretStore by secrets.keyring {
                override suspend fun write(
                    key: SecretKey,
                    secret: Secret,
                ): Unit = throw SecretStoreUnavailableException("Jotter cannot reach a keyring.")
            }
        val stores = SecretStores(noKeyring, secrets.file, "/config/jotter/secrets.json")
        SyncedDevice("here", TestSecrets(secrets.keyring, secrets.file, stores)).use { device ->
            screen(device) {
                addServer()
                awaitText(SET_UP_SERVER_TITLE)
                clickInDialog("Cancel")
                onNodeWithText(ADD_SERVER).performClick()
                clickInDialog("Set up and add")
                awaitText(NO_KEYRING_TITLE)
                awaitText("Jotter cannot reach a keyring. Jotter can keep the app password")
                clickInDialog("Cancel")
                onNodeWithText(ADD_SERVER).performClick()
                clickInDialog("Set up and add")
                awaitText(NO_KEYRING_TITLE)
                clickInDialog("Keep it in the file")
                awaitText("Its password is kept in plain text in /config/jotter/secrets.json")
                assertEquals(1, secrets.file.secrets.size)
                assertTrue(secrets.keyring.secrets.isEmpty())
            }
        }
    }

    @Test
    fun plainHttpIsRefusedWithTheReason() =
        SyncedDevice("here").use { device ->
            screen(device) {
                addServer("http://dav.example.org/jotter/")
                awaitText("Plain http would send the password and every note unencrypted")
            }
        }
}
