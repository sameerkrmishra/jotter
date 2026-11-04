package io.github.sameerkrmishra.jotter.app.android

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sameerkrmishra.jotter.app.sync.EXPORT_DATABASE_EXPLAINED
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TIMEOUT_MS = 10_000L

/** The app's Activity with the shared screens, on a database of its own. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val test = TestGraph()

    @After
    fun cleanUp() {
        test.close()
    }

    private fun ComposeTestRule.awaitText(text: String) =
        waitUntil(TIMEOUT_MS) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun pressBack(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun aNoteWrittenInTheAppIsStoredAndBackLeavesTheEditor() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.awaitText("New note")
            compose.onNodeWithText("New note").performClick()
            compose.onNodeWithTag("body").performTextInput("- [ ] milk")
            pressBack(scenario)
            compose.awaitText("- [ ] milk")
            assertEquals(
                "- [ ] milk",
                test.ready.store.notes
                    .list()
                    .single()
                    .body,
            )
            // Back on the note list leaves the app as any app does: it is finished, or from
            // Android 12 moved to the background.
            pressBack(scenario)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            compose.waitUntil(TIMEOUT_MS) { scenario.state != Lifecycle.State.RESUMED }
        }
    }

    @Test
    fun aDamagedDatabaseIsExplainedLeftAloneAndTheDebugLogShown() {
        val name = "${test.folder.name}-damaged.db"
        val damaged = test.context.getDatabasePath(name)
        checkNotNull(damaged.parentFile).mkdirs()
        val garbage = "not SQLite, but long enough to be read as a header by SQLite itself"
        damaged.writeText(garbage)
        val graph =
            AppGraph(
                test.context,
                databaseName = name,
                secretsFile = File(test.folder, "s.json"),
                keyAlias = test.keyAlias,
            )
        (test.context as JotterApplication).replaceGraph(graph)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(TIMEOUT_MS) {
                    compose
                        .onAllNodesWithText("so Jotter cannot read it", substring = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                compose.onNodeWithText("Show debug log").performClick()
                compose.waitUntil(TIMEOUT_MS) {
                    compose
                        .onAllNodesWithText("App: android", substring = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
            }
            assertEquals(garbage, damaged.readText())
        } finally {
            graph.close()
            damaged.delete()
        }
    }

    @Test
    fun emojiAreEditedAndShownWithoutEmojiCompat() {
        // EmojiCompat's initializer is removed from the manifest, so the system font draws them.
        // An emoji of Unicode 15, newer than API 26's font, must still be kept whole.
        val text = "tea 🙂 🫨"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.awaitText("New note")
            compose.onNodeWithText("New note").performClick()
            compose.onNodeWithTag("body").performTextInput(text)
            pressBack(scenario)
            compose.awaitText(text)
            assertEquals(
                text,
                test.ready.store.notes
                    .list()
                    .single()
                    .body,
            )
        }
    }

    @Test
    fun turningThePhoneKeepsTheDraft() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.awaitText("New note")
            compose.onNodeWithText("New note").performClick()
            compose.onNodeWithTag("body").performTextInput("draft")
            scenario.recreate()
            compose.awaitText("draft")
        }
    }

    @Test
    fun leavingTheScreenStoresTheDraft() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.awaitText("New note")
            compose.onNodeWithText("New note").performClick()
            compose.onNodeWithTag("body").performTextInput("kept")
            scenario.moveToState(Lifecycle.State.CREATED)
            compose.waitUntil(TIMEOUT_MS) {
                test.ready.store.notes
                    .list()
                    .any { it.body == "kept" }
            }
        }
    }

    /** The input type that the focused field asks the keyboard for. */
    private fun focusedInputType(scenario: ActivityScenario<MainActivity>): Int {
        var type = 0
        scenario.onActivity { activity ->
            val view = checkNotNull(activity.window.decorView.findFocus()) { "nothing focused" }
            val info = EditorInfo()
            checkNotNull(view.onCreateInputConnection(info)) { "no text input" }
            type = info.inputType
        }
        return type
    }

    private fun focus(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun passwordsAndTokensAskForAPasswordKeyboardWithoutAutocorrection() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.awaitText("Sync")
            compose.onNodeWithText("Sync").performClick()
            compose.awaitText(FOLDERS_UNAVAILABLE)
            // The WebDAV form's app password and the hub's pairing token.
            listOf("server-password", "hub-token").forEach { tag ->
                focus(tag)
                val type = focusedInputType(scenario)
                assertEquals(InputType.TYPE_CLASS_TEXT, type and InputType.TYPE_MASK_CLASS, tag)
                assertEquals(
                    InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    type and InputType.TYPE_MASK_VARIATION,
                    tag,
                )
                assertEquals(0, type and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT, tag)
            }
            // The check can tell: the URL is an ordinary field.
            focus("server-url")
            val url = focusedInputType(scenario)
            assertTrue(
                url and InputType.TYPE_MASK_VARIATION != InputType.TYPE_TEXT_VARIATION_PASSWORD,
            )
        }
    }

    @Test
    fun theSyncScreenOffersWebDavAndTheHubButNoFolder() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.awaitText("Sync")
            compose.onNodeWithText("Sync").performClick()
            compose.awaitText(FOLDERS_UNAVAILABLE)
            compose.onNodeWithText("Add server").assertExists()
            compose.onNodeWithText("Add PC hub").assertExists()
            assertTrue(compose.onAllNodesWithText("Add folder").fetchSemanticsNodes().isEmpty())
            pressBack(scenario)
            compose.awaitText("New note")
        }
    }

    @Test
    fun theSyncScreenOffersExportDatabaseAndSaysSecretsAreNotInIt() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.awaitText("Sync")
            compose.onNodeWithText("Sync").performClick()
            compose.awaitText("Sync now")
            compose.onNodeWithText("Export database…").performScrollTo().performClick()
            compose.waitUntil(TIMEOUT_MS) {
                compose
                    .onAllNodesWithText(EXPORT_DATABASE_EXPLAINED)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("Choose where…").assertExists()
            compose.onNodeWithText("Cancel").performClick()
            compose.waitForIdle()
            assertTrue(
                compose.onAllNodesWithText(EXPORT_DATABASE_EXPLAINED).fetchSemanticsNodes().isEmpty(),
            )
        }
    }
}
