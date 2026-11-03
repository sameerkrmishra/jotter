@file:OptIn(ExperimentalTestApi::class, ExperimentalPathApi::class)

package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.sameerkrmishra.jotter.core.sync.ConflictResolver
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val TIMEOUT_MS = 20_000L
private const val LAPTOP = "laptop"
private const val DESKTOP = "desktop"
private const val SETTLE_ROUNDS = 5

/**
 * Two apps side by side, each with a database of its own and the real sync stack of the desktop
 * app: engine, backend manager and scheduler, over one real folder. Everything the user does, they
 * do on the screen: add the folder, write and edit a note, sync, and find the conflict.
 */
@Suppress("InjectDispatcher") // The test stands in for two composition roots.
class TwoAppsEndToEndTest {
    private val laptop = SyncedDevice(LAPTOP)
    private val desktop = SyncedDevice(DESKTOP)
    private val folders: Path = createTempDirectory("jotter-e2e")
    private val stick: Path = folders.resolve("stick").createDirectories()

    @AfterTest
    fun cleanUp() {
        laptop.close()
        desktop.close()
        folders.deleteRecursively()
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

    private fun ComposeUiTest.addStick(app: String) {
        click(app, BACKENDS_TITLE)
        node(app, hasTestTag(FOLDER_FIELD)).performTextInput(stick.toString())
        click(app, "Add folder")
    }

    /** Syncs [device] with the button on its backend screen, and waits for the outcome. */
    private fun ComposeUiTest.syncNow(
        app: String,
        device: SyncedDevice,
    ) {
        val before = lastAttempt(device)
        click(app, BACKENDS_TITLE)
        await(app, "Sync now")
        click(app, "Sync now")
        waitUntil(timeoutMillis = TIMEOUT_MS) { lastAttempt(device) != before }
        click(app, "Back")
    }

    private fun lastAttempt(device: SyncedDevice) =
        device.store.backends
            .list()
            .single()
            .lastAttempt

    /**
     * The segment files on the stick, of every device. Not `Files.walk`: a sync renames temporary
     * files meanwhile, and that walk fails on one that went between listing and looking at it.
     */
    private fun segments(): Int =
        stick.toFile().walkTopDown().count { it.name.endsWith(".json.gz") }

    /** Waits until [device] has synced, after edits and after what it brought in, and is idle. */
    private fun ComposeUiTest.settle(device: SyncedDevice) {
        // A round that brought something in is followed by one more, AFTER_EDIT_MS later.
        Thread.sleep(SyncedDevice.AFTER_EDIT_MS * SETTLE_ROUNDS)
        waitUntil(timeoutMillis = TIMEOUT_MS) { !device.scheduler.syncing.value }
    }

    private fun ComposeUiTest.edit(
        app: String,
        device: SyncedDevice,
        body: String,
    ) {
        val note =
            device.store.notes
                .list()
                .single()
        node(app, hasTestTag(cardTag(note.id))).performSemanticsAction(SemanticsActions.OnClick)
        node(app, hasTestTag(BODY_FIELD)).performTextReplacement(body)
        click(app, "Back")
        waitUntil(timeoutMillis = TIMEOUT_MS) {
            device.store.notes
                .list()
                .single()
                .body == body
        }
    }

    private fun ComposeUiTest.showBothApps() {
        setContent {
            Row(Modifier.fillMaxSize()) {
                listOf(LAPTOP to laptop, DESKTOP to desktop).forEach { (name, device) ->
                    Box(Modifier.weight(1f).testTag(name)) {
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
            }
        }
    }

    private fun ComposeUiTest.addTheStickToBoth() {
        // The laptop sets the empty folder up, after saying what that does.
        addStick(LAPTOP)
        waitUntil(timeoutMillis = TIMEOUT_MS) {
            onAllNodes(hasText(SET_UP_TITLE) and hasAnyAncestor(isDialog()))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        onNode(hasText("Set up and add") and hasAnyAncestor(isDialog())).performClick()
        await(LAPTOP, "Added stick. It syncs now.")
        click(LAPTOP, "Back")
        // The desktop finds a store there, which needs no question.
        addStick(DESKTOP)
        await(DESKTOP, "Added stick. It syncs now.")
        click(DESKTOP, "Back")
    }

    @Test
    fun twoAppsConvergeThroughAFolderAndShowTheirConflict() =
        runComposeUiTest {
            laptop.start()
            desktop.start()
            showBothApps()
            addTheStickToBoth()

            // A note written on the laptop goes out a moment after it is saved.
            click(LAPTOP, "New note")
            node(LAPTOP, hasTestTag(TITLE_FIELD)).performTextInput("Groceries")
            node(LAPTOP, hasTestTag(BODY_FIELD)).performTextInput("eggs\nmilk\nbread")
            click(LAPTOP, "Back")
            waitUntil(timeoutMillis = TIMEOUT_MS) { segments() > 0 }
            settle(laptop)
            syncNow(DESKTOP, desktop)
            await(DESKTOP, "Groceries")
            assertEquals(laptop.store.notes.list(), desktop.store.notes.list())
            settle(desktop)

            // Both change the same line before either has seen the other's change. The laptop's
            // goes out a moment after it is saved; the desktop has not synced since.
            val sent = segments()
            edit(LAPTOP, laptop, "eggs\noat milk\nbread")
            waitUntil(timeoutMillis = TIMEOUT_MS) { segments() > sent }
            edit(DESKTOP, desktop, "eggs\nsoy milk\nbread")
            // The desktop's sync after its edit brings the laptop's in: a conflict.
            await(DESKTOP, "$NEEDS_ATTENTION (1)")
            settle(desktop)
            syncNow(LAPTOP, laptop)
            await(LAPTOP, "$NEEDS_ATTENTION (1)")
            settle(laptop)

            // Both show the same version and keep the same other one, for the user to decide.
            val here = ConflictResolver(laptop.store).open()
            val there = ConflictResolver(desktop.store).open()
            assertEquals(1, here.size)
            assertEquals(here, there)
            assertEquals(laptop.store.notes.list(), desktop.store.notes.list())

            click(LAPTOP, "$NEEDS_ATTENTION (1)")
            await(LAPTOP, "Needs attention: $CONFLICT_LABEL")
            click(LAPTOP, "Review")
            await(LAPTOP, CONFLICT_HEADING)
            await(LAPTOP, "oat milk", substring = true)
            await(LAPTOP, "soy milk", substring = true)
        }
}
