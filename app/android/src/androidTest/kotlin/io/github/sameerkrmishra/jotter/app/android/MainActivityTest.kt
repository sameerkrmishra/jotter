package io.github.sameerkrmishra.jotter.app.android

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
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
}
