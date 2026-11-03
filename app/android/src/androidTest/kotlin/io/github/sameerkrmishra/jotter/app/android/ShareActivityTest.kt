package io.github.sameerkrmishra.jotter.app.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TIMEOUT_MS = 10_000L

/** Sharing to Jotter from another app, on a database of its own. */
@RunWith(AndroidJUnit4::class)
class ShareActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val test = TestGraph()

    @After
    fun cleanUp() {
        test.close()
    }

    private fun send(build: Intent.() -> Unit): Intent =
        Intent(test.context, ShareActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .apply(build)

    private fun ComposeTestRule.awaitText(
        text: String,
        substring: Boolean = false,
    ) = waitUntil(TIMEOUT_MS) {
        onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    private fun notes() =
        test.ready.store.notes
            .list()

    @Test
    fun sharedTextBecomesANote() {
        val intent =
            send {
                putExtra(Intent.EXTRA_TEXT, "https://example.org/recipe")
                putExtra(Intent.EXTRA_SUBJECT, "Lentil soup")
            }
        ActivityScenario.launch<ShareActivity>(intent).use { scenario ->
            compose.awaitText(SAVED)
            val note = notes().single()
            assertEquals("Lentil soup", note.title)
            assertEquals("https://example.org/recipe", note.body)
            // Turning the phone does not save it twice.
            scenario.recreate()
            compose.awaitText(SAVED)
            assertEquals(1, notes().size)
            compose.onNodeWithText("Done").performClick()
            compose.waitUntil(TIMEOUT_MS) { scenario.state == Lifecycle.State.DESTROYED }
        }
    }

    @Test
    fun aLargeShareIsStoredWhole() {
        // 110,000 characters. Android hands an intent over in one binder transaction of at most
        // 1 MB, and a text of about 330,000 characters already fails in the system, before Jotter.
        val text = "- [ ] item\n".repeat(10_000)
        ActivityScenario.launch<ShareActivity>(send { putExtra(Intent.EXTRA_TEXT, text) }).use {
            compose.awaitText(SAVED)
            assertEquals(text, notes().single().body)
        }
    }

    @Test
    fun anImageIsRefusedPlainly() {
        val intent =
            send {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, Uri.parse("content://example/picture.png"))
            }
        ActivityScenario.launch<ShareActivity>(intent).use {
            compose.awaitText(FILES_REFUSED)
            compose.onNodeWithText("Close").assertExists()
            assertTrue(notes().isEmpty())
        }
    }

    @Test
    fun textBesideAFileIsSavedAndTheFileNamed() {
        val intent =
            send {
                putExtra(Intent.EXTRA_TEXT, "look at this")
                putExtra(Intent.EXTRA_STREAM, Uri.parse("content://example/picture.png"))
            }
        ActivityScenario.launch<ShareActivity>(intent).use {
            compose.awaitText(ATTACHMENT_LEFT_OUT, substring = true)
            assertEquals("look at this", notes().single().body)
        }
    }

    @Test
    fun anExtraJotterCannotReadIsRefusedNotACrash() {
        val intent =
            send {
                putExtra(Intent.EXTRA_TEXT, "milk")
                putExtra("from.another.app", Unreadable())
            }
        ActivityScenario.launch<ShareActivity>(intent).use {
            // Up to Android 12 the first extra read unparcels them all, and the share is refused.
            // From Android 13 each extra is unparcelled when read, so the text is still saved.
            compose.waitUntil(TIMEOUT_MS) {
                listOf(SHARE_UNREADABLE, SAVED).any {
                    compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty()
                }
            }
            val refused = compose.onAllNodesWithText(SHARE_UNREADABLE).fetchSemanticsNodes()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                assertEquals("milk", notes().single().body)
            } else {
                assertTrue(refused.isNotEmpty())
                assertTrue(notes().isEmpty())
            }
        }
    }

    @Test
    fun anEmptyShareSavesNothing() {
        ActivityScenario.launch<ShareActivity>(send { putExtra(Intent.EXTRA_TEXT, " ") }).use {
            compose.awaitText(NOTHING_TO_SAVE)
            assertTrue(notes().isEmpty())
            compose.onNodeWithTag(SHARE_MESSAGE).assertExists()
        }
    }
}
