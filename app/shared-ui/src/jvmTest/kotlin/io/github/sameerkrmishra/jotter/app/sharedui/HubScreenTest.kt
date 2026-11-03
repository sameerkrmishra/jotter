@file:OptIn(ExperimentalTestApi::class, ExperimentalPathApi::class)

package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.sameerkrmishra.jotter.app.sync.Secret
import io.github.sameerkrmishra.jotter.app.sync.SecretKey
import io.github.sameerkrmishra.jotter.app.sync.SecretStore
import io.github.sameerkrmishra.jotter.app.sync.SecretStoreUnavailableException
import io.github.sameerkrmishra.jotter.app.sync.SecretStores
import io.github.sameerkrmishra.jotter.core.sync.ConflictResolver
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val TIMEOUT_MS = 20_000L

/** The hub section and the hub's card, on one app whose keyring does not answer. */
@Suppress("InjectDispatcher") // The test stands in for a composition root.
class HubScreenTest {
    private val folders: Path = createTempDirectory("jotter-hub-screen")
    private val secrets = TestSecrets()
    private val noKeyring =
        object : SecretStore by secrets.keyring {
            override suspend fun write(
                key: SecretKey,
                secret: Secret,
            ): Unit = throw SecretStoreUnavailableException("Jotter cannot reach a keyring.")
        }
    private val device =
        SyncedDevice(
            "pc",
            TestSecrets(secrets.keyring, secrets.file, SecretStores(noKeyring, secrets.file, "/f")),
            folders,
        )

    @AfterTest
    fun cleanUp() {
        device.close()
        folders.deleteRecursively()
    }

    private fun ComposeUiTest.awaitText(text: String) =
        waitUntil(timeoutMillis = TIMEOUT_MS) {
            onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }

    private fun ComposeUiTest.click(text: String) =
        onNodeWithText(text).also { runCatching { it.performScrollTo() } }.performClick()

    private fun ComposeUiTest.type(
        tag: String,
        text: String,
    ) = onNodeWithTag(
        tag,
    ).also { runCatching { it.performScrollTo() } }.performTextReplacement(text)

    private fun ComposeUiTest.clickInDialog(text: String) =
        onNode(hasText(text) and hasAnyAncestor(isDialog())).performClick()

    @Test
    fun theHubIsRunPairedAndAddedHere() =
        runComposeUiTest {
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
            click(BACKENDS_TITLE)
            awaitText("The hub is off.")
            awaitText(HUB_ADDRESSES)
            type(HUB_PORT_FIELD, "a port")
            click("Turn hub on")
            awaitText("The port must be a number")
            click("Dismiss")
            type(HUB_PORT_FIELD, "0")
            click("Turn hub on")
            awaitText("The hub is on, at 127.0.0.1:")
            val address = (device.hub!!.state.value as HubState.Running).address

            type(HUB_DEVICE_FIELD, "laptop")
            click("Pair device")
            awaitText(PAIRED_TITLE)
            awaitText(pairedExplanation("laptop"))
            val token =
                onAllNodes(hasAnyAncestor(isDialog()))
                    .fetchSemanticsNodes()
                    .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                    .map { it.text }
                    .single { Regex("[0-9a-f]{32}\\..+").matches(it) }
            clickInDialog("Done")

            type(HUB_ADDRESS_FIELD, address)
            type(HUB_TOKEN_FIELD, token)
            awaitText(HUB_CLIENT_RULE)
            click(ADD_HUB)
            awaitText(NO_KEYRING_TITLE)
            clickInDialog("Keep it in the file")
            awaitText("Added PC hub. It syncs now.")
            awaitText("Its pairing token is kept in plain text in /f")
            assertEquals(1, secrets.file.secrets.size)

            click(NEW_PASSWORD)
            awaitText("Enter a new pairing token")
            onNodeWithTag(NEW_PASSWORD_FIELD).performTextReplacement("0".repeat(32) + ".wrong")
            clickInDialog("Check and keep")
            awaitText("The hub refused this pairing token too.")
            clickInDialog("Cancel")

            click("Unpair laptop")
            awaitText("Unpaired laptop.")
            click("Turn hub off")
            awaitText("The hub is off.")
        }
}
