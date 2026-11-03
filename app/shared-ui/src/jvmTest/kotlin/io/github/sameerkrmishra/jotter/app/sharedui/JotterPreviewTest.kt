@file:OptIn(ExperimentalTestApi::class)

package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.AnnotatedString
import io.github.sameerkrmishra.jotter.app.sharedui.markdown.MARKDOWN_PREVIEW
import io.github.sameerkrmishra.jotter.app.sharedui.markdown.taskTag
import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.storage.NoteService
import io.github.sameerkrmishra.jotter.core.sync.ConflictResolver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val TIMEOUT_MS = 10_000L

/** The preview in the real app, on a real temporary store. */
@Suppress("InjectDispatcher") // The tests stand in for a composition root.
class JotterPreviewTest {
    private val database = TestDatabase()

    @AfterTest
    fun deleteDatabase() {
        database.delete()
    }

    private fun app(
        io: CoroutineDispatcher = Dispatchers.IO,
        block: ComposeUiTest.(NoteService) -> Unit,
    ) = database.open(Dispatchers.IO).use { store ->
        runComposeUiTest {
            setContent {
                val scope = rememberCoroutineScope()
                JotterApp(
                    remember { AppViewModel(store.notes, ConflictResolver(store), scope, io) },
                )
            }
            block(store.notes)
        }
    }

    private fun ComposeUiTest.awaitTag(tag: String) =
        waitUntil(timeoutMillis = TIMEOUT_MS) {
            onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }

    private fun ComposeUiTest.awaitText(text: String) =
        waitUntil(timeoutMillis = TIMEOUT_MS) {
            onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }

    private fun ComposeUiTest.openPreview(note: Note) {
        openNote(note)
        onNodeWithText("Preview").performClick()
        awaitTag(MARKDOWN_PREVIEW)
    }

    private fun ComposeUiTest.openNote(note: Note) {
        waitUntil(timeoutMillis = TIMEOUT_MS) {
            onAllNodesWithTag(cardTag(note.id)).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(cardTag(note.id)).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    private fun ComposeUiTest.awaitStored(
        notes: NoteService,
        note: Note,
        body: String,
    ) = waitUntil(timeoutMillis = TIMEOUT_MS) { notes.get(note.id)?.body == body }

    @Test
    fun theBodyTogglesBetweenTextAndPreview() =
        app { notes ->
            val note = notes.create("", "# Plan\n\n- [ ] milk")
            openPreview(note)

            awaitText("Plan")
            assertEquals(0, onAllNodesWithTag(BODY_FIELD).fetchSemanticsNodes().size)

            onNodeWithText("Edit").performClick()

            onNodeWithTag(BODY_FIELD).assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    AnnotatedString("# Plan\n\n- [ ] milk"),
                ),
            )
        }

    @Test
    fun aTappedBoxReachesTheStoreThroughSave() =
        app { notes ->
            val note = notes.create("", "- [ ] milk\n- [ ] milk\n")
            openPreview(note)
            awaitTag(taskTag(14))

            onNodeWithTag(taskTag(14)).performClick()
            awaitTag(taskTag(14))
            waitUntil(timeoutMillis = TIMEOUT_MS) {
                runCatching { onNodeWithTag(taskTag(14)).assertIsOn() }.isSuccess
            }
            onNodeWithText("Save").performClick()

            awaitStored(notes, note, "- [ ] milk\n- [x] milk\n")
        }

    @Test
    fun ctrlSSavesFromThePreview() =
        app { notes ->
            val note = notes.create("", "- [ ] milk")
            openPreview(note)
            awaitTag(taskTag(3))
            onNodeWithTag(taskTag(3)).performClick()

            onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.S) } }

            awaitStored(notes, note, "- [x] milk")
        }

    @Test
    fun theFieldKeepsWhatWasTappedAfterSwitchingBack() =
        app { notes ->
            val note = notes.create("", "- [ ] milk")
            openPreview(note)
            awaitTag(taskTag(3))
            onNodeWithTag(taskTag(3)).performClick()

            onNodeWithText("Edit").performClick()
            onNodeWithTag(BODY_FIELD).performTextInput("more ")
            onNodeWithText("Save").performClick()

            awaitStored(notes, note, "more - [x] milk")
        }

    // Only holding the parse makes this independent of how fast the machine is: if it ran on the
    // UI thread, the dispatcher would never see it, and the preview would be there already.
    @Test
    fun theUiStaysUsableWhileTheParseHasNotFinished() {
        val io = HeldDispatcher(Dispatchers.IO)
        app(io) { notes ->
            val note = notes.create("", "- [ ] milk")
            openNote(note)
            io.hold()

            onNodeWithText("Preview").performClick()

            waitUntil(timeoutMillis = TIMEOUT_MS) { io.heldBlocks > 0 }
            waitForIdle()
            assertEquals(0, onAllNodesWithTag(MARKDOWN_PREVIEW).fetchSemanticsNodes().size)
            onNodeWithTag(TITLE_FIELD).performTextInput("typed meanwhile")
            onNodeWithTag(TITLE_FIELD).assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    AnnotatedString("typed meanwhile"),
                ),
            )

            io.release()

            awaitTag(taskTag(3))
        }
    }

    /** Runs blocks on [target], except that while held it keeps them until [release]. */
    private class HeldDispatcher(
        private val target: CoroutineDispatcher,
    ) : CoroutineDispatcher() {
        private val held = ArrayDeque<Pair<CoroutineContext, Runnable>>()
        private var isHolding = false

        val heldBlocks: Int
            get() = synchronized(held) { held.size }

        fun hold() {
            synchronized(held) { isHolding = true }
        }

        fun release() {
            synchronized(held) {
                isHolding = false
                held.forEach { (context, block) -> target.dispatch(context, block) }
                held.clear()
            }
        }

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            synchronized(held) {
                if (isHolding) held += context to block else target.dispatch(context, block)
            }
        }
    }
}
