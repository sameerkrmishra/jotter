@file:OptIn(ExperimentalTestApi::class, ExperimentalPathApi::class)

package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.sameerkrmishra.jotter.core.sync.ConflictResolver
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val TIMEOUT_MS = 30_000L
private const val PC = "pc"
private const val LAPTOP = "laptop"

/**
 * The PC runs the hub, on 127.0.0.1 only, and a laptop syncs through it: two apps side by side,
 * each with a database of its own and the desktop app's real sync stack. Everything happens on the
 * screen: turning the hub on, a refused address, pairing with the token shown once, adding the hub
 * on the laptop, a note arriving, and unpairing.
 */
@Suppress("InjectDispatcher") // The test stands in for two composition roots.
class TwoAppsHubTest {
    private val folders: Path = createTempDirectory("jotter-hub-e2e")
    private val pc = SyncedDevice(PC, hubFolder = folders)
    private val laptop = SyncedDevice(LAPTOP)

    @AfterTest
    fun cleanUp() {
        pc.close()
        laptop.close()
        folders.deleteRecursively()
    }

    @Test
    fun aLaptopSyncsThroughTheHubOnThePc() =
        runComposeUiTest {
            pc.start()
            laptop.start()
            setContent {
                Row(Modifier.fillMaxSize()) {
                    App(PC, pc, Modifier.weight(1f))
                    App(LAPTOP, laptop, Modifier.weight(1f))
                }
            }
            click(PC, BACKENDS_TITLE)
            click(LAPTOP, BACKENDS_TITLE)

            // The hub refuses an address outside loopback and Tailscale, and says why.
            type(PC, HUB_HOST_FIELD, "192.168.1.5")
            click(PC, "Turn hub on")
            await(PC, "The hub only listens on loopback", substring = true)
            click(PC, "Dismiss")
            type(PC, HUB_HOST_FIELD, "127.0.0.1")
            click(PC, "Turn hub on")
            await(PC, "The hub is on, at 127.0.0.1:", substring = true)
            val address = (pc.hub!!.state.value as HubState.Running).address

            // The token shows once.
            type(PC, HUB_DEVICE_FIELD, LAPTOP)
            click(PC, "Pair device")
            waitUntil(timeoutMillis = TIMEOUT_MS) {
                onAllNodes(hasText(PAIRED_TITLE)).fetchSemanticsNodes().isNotEmpty()
            }
            val token = tokenInDialog()
            onNode(hasText("Done") and hasAnyAncestor(isDialog())).performClick()
            await(PC, "Paired: $LAPTOP")

            // The laptop refuses a hub outside the tailnet before sending the token.
            type(LAPTOP, HUB_ADDRESS_FIELD, "192.168.1.5")
            type(LAPTOP, HUB_TOKEN_FIELD, token)
            click(LAPTOP, ADD_HUB)
            await(LAPTOP, "192.168.1.5 is not on your tailnet or this machine.", substring = true)
            click(LAPTOP, "Dismiss")
            type(LAPTOP, HUB_ADDRESS_FIELD, address)
            click(LAPTOP, ADD_HUB)
            await(LAPTOP, "Added PC hub. It syncs now.")
            await(LAPTOP, "Its pairing token is kept in the keyring.")

            val note = pc.store.notes.create("", "Bought milk, through the hub")
            waitUntil(timeoutMillis = TIMEOUT_MS) { pushed(pc) }
            click(LAPTOP, "Sync now")
            waitUntil(timeoutMillis = TIMEOUT_MS) { laptop.store.notes.get(note.id) != null }
            assertEquals(
                "Bought milk, through the hub",
                laptop.store.notes
                    .get(note.id)
                    ?.body,
            )

            click(PC, "Unpair $LAPTOP")
            await(PC, "Unpaired $LAPTOP. Its token no longer works.")
            click(LAPTOP, "Sync now")
            await(LAPTOP, "The PC hub refused this device's pairing token", substring = true)

            click(PC, "Turn hub off")
            await(PC, "The hub is off.")
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

    /** The one text in the open dialog that looks like a token: hex digits, a dot, the secret. */
    private fun ComposeUiTest.tokenInDialog(): String =
        onAllNodes(hasAnyAncestor(isDialog()))
            .fetchSemanticsNodes()
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
            .map { it.text }
            .single { Regex("[0-9a-f]{32}\\..+").matches(it) }

    /** Whether [device]'s last sync of its first backend sent its note. */
    private fun pushed(device: SyncedDevice): Boolean =
        device.store.backends
            .list()
            .firstOrNull()
            ?.lastAttempt
            ?.detail
            ?.contains("sent 1 file") == true

    private fun inApp(app: String) = hasAnyAncestor(hasTestTag(app))

    private fun ComposeUiTest.node(
        app: String,
        matcher: SemanticsMatcher,
    ) = onNode(matcher and inApp(app))

    private fun ComposeUiTest.click(
        app: String,
        text: String,
    ) = node(app, hasText(text)).also { runCatching { it.performScrollTo() } }.performClick()

    private fun ComposeUiTest.type(
        app: String,
        tag: String,
        text: String,
    ) = node(app, hasTestTag(tag))
        .also {
            runCatching { it.performScrollTo() }
        }.performTextReplacement(text)

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
