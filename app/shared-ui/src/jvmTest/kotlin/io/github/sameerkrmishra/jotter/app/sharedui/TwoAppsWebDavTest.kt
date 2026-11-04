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
import io.github.sameerkrmishra.jotter.app.sync.AddResult
import io.github.sameerkrmishra.jotter.app.sync.Secret
import io.github.sameerkrmishra.jotter.app.sync.SecretKey
import io.github.sameerkrmishra.jotter.app.sync.SecretStore
import io.github.sameerkrmishra.jotter.app.sync.SecretStoreUnavailableException
import io.github.sameerkrmishra.jotter.app.sync.SecretStores
import io.github.sameerkrmishra.jotter.core.sync.ConflictResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

                // Removed with a keyring that answers: its password goes with it.
                onNodeWithText("Remove").performClick()
                clickInDialog("Remove")
                awaitText("Removed 127.0.0.1. Its files stay in ${server.url}.")
                waitUntil(timeoutMillis = TIMEOUT_MS) {
                    device.secrets.keyring.secrets
                        .isEmpty()
                }
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
    fun restoredWithoutAKeyringThePasswordMayGoInTheFile() {
        // The backend as another computer added it, with its password in that computer's keyring.
        val record =
            SyncedDevice("there").use { there ->
                val added =
                    runBlocking {
                        there.manager.addWebDav(
                            server.url,
                            "me",
                            Secret(server.password),
                            setUpNewStore = true,
                        )
                    }
                assertIs<AddResult.Added>(added)
                there.store.backends
                    .list()
                    .single()
            }
        val secrets = TestSecrets()
        val noKeyring =
            object : SecretStore {
                override suspend fun read(key: SecretKey): Secret? = throw unavailable()

                override suspend fun write(
                    key: SecretKey,
                    secret: Secret,
                ): Unit = throw unavailable()

                override suspend fun delete(key: SecretKey): Unit = throw unavailable()

                fun unavailable() =
                    SecretStoreUnavailableException("Jotter cannot reach a keyring.")
            }
        val stores = SecretStores(noKeyring, secrets.file, "/config/jotter/secrets.json")
        SyncedDevice("here", TestSecrets(secrets.keyring, secrets.file, stores)).use { device ->
            device.store.backends.add(record)
            screen(device) {
                onNodeWithText(NEW_PASSWORD).performClick()
                awaitText(PASSWORD_TITLE)
                onNodeWithTag(NEW_PASSWORD_FIELD).performTextReplacement(server.password)
                clickInDialog("Check and keep")
                awaitText(NO_KEYRING_TITLE)
                awaitText("Jotter cannot reach a keyring. Jotter can keep the app password")
                clickInDialog("Cancel")
                // Back to the password, which nothing stored.
                awaitText(PASSWORD_TITLE)
                assertTrue(secrets.file.secrets.isEmpty())
                clickInDialog("Check and keep")
                awaitText(NO_KEYRING_TITLE)
                clickInDialog("Keep it in the file")
                awaitText("in the file, in plain text. It syncs now.")
                awaitText("Jotter could not delete the old copy")
                awaitText("Its password is kept in plain text in /config/jotter/secrets.json")
                waitUntil(timeoutMillis = TIMEOUT_MS) {
                    device.store.backends
                        .list()
                        .single()
                        .lastAttempt
                        ?.outcome == "SYNCED"
                }
                assertEquals(1, secrets.file.secrets.size)
            }
        }
    }

    @Test
    fun withoutAKeyringItCanBeRemovedAnyway() {
        val secrets = TestSecrets()
        val noDelete =
            object : SecretStore by secrets.keyring {
                override suspend fun delete(key: SecretKey): Unit =
                    throw SecretStoreUnavailableException("Jotter cannot reach a keyring.")
            }
        val stores = SecretStores(noDelete, secrets.file, "/config/jotter/secrets.json")
        SyncedDevice("here", TestSecrets(secrets.keyring, secrets.file, stores)).use { device ->
            screen(device) {
                addServer()
                clickInDialog("Set up and add")
                awaitText("It syncs now.")
                onNodeWithText("Remove").performClick()
                clickInDialog("Remove")
                awaitText(REMOVE_ANYWAY_TITLE)
                awaitText("Jotter cannot reach a keyring. You can remove the backend anyway.")
                awaitText("secret-tool clear application jotter backend")
                clickInDialog("Keep it")
                assertEquals(
                    1,
                    device.store.backends
                        .list()
                        .size,
                )

                onNodeWithText("Remove").performClick()
                clickInDialog("Remove")
                awaitText(REMOVE_ANYWAY_TITLE)
                clickInDialog("Remove anyway")
                awaitText("Removed 127.0.0.1. Its files stay in")
                awaitText("may still be in your keyring")
                assertEquals(
                    0,
                    device.store.backends
                        .list()
                        .size,
                )
                assertEquals(1, secrets.keyring.secrets.size)
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
