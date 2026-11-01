@file:OptIn(ExperimentalTestApi::class)

package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.AnnotatedString
import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.TagName
import io.github.sameerkrmishra.jotter.core.storage.Highlighted
import io.github.sameerkrmishra.jotter.core.storage.NoteService
import kotlinx.coroutines.Dispatchers
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TIMEOUT_MS = 10_000L

/**
 * The real screens on a real temporary store. Writes run on [Dispatchers.IO] as in the app, so the
 * tests wait for their result to appear.
 */
@Suppress("InjectDispatcher") // The tests stand in for a composition root.
class JotterAppTest {
    private val database = TestDatabase()

    @AfterTest
    fun deleteDatabase() {
        database.delete()
    }

    private fun app(block: ComposeUiTest.(NoteService) -> Unit) =
        database.open(Dispatchers.IO).use { store ->
            runComposeUiTest {
                setContent {
                    val scope = rememberCoroutineScope()
                    JotterApp(remember { AppViewModel(store.notes, scope, Dispatchers.IO) })
                }
                block(store.notes)
            }
        }

    private fun ComposeUiTest.awaitText(
        text: String,
        substring: Boolean = false,
    ) = waitUntil(timeoutMillis = TIMEOUT_MS) {
        onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    private fun ComposeUiTest.awaitNoText(text: String) =
        waitUntil(timeoutMillis = TIMEOUT_MS) {
            onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        }

    private fun ComposeUiTest.click(text: String) = onNodeWithText(text).performClick()

    /** Clicks the button [text] on the card of [note]. */
    private fun ComposeUiTest.clickOn(
        note: Note,
        text: String,
    ) = onNode(hasText(text) and hasAnyAncestor(hasTestTag(cardTag(note.id)))).performClick()

    // Not performClick: the middle of a short card is one of its buttons.
    private fun ComposeUiTest.openNote(note: Note) {
        onNodeWithTag(cardTag(note.id)).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    private fun ComposeUiTest.fieldShows(
        tag: String,
        text: String,
    ) = onNodeWithTag(tag).assert(
        SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText,
            AnnotatedString(text),
        ),
    )

    @Test
    fun aNewNoteIsSavedWhenLeavingTheEditor() =
        app { notes ->
            awaitText("Nothing here.")
            click("New note")
            onNodeWithTag(TITLE_FIELD).performTextInput("Groceries")
            onNodeWithTag(BODY_FIELD).performTextInput("milk")

            click("Back")

            awaitText("Groceries")
            assertEquals("milk", notes.list().single().body)
        }

    @Test
    fun theSaveButtonNeedsAChange() =
        app { notes ->
            val note = notes.create("title", "body")
            awaitText("title")
            openNote(note)
            onNodeWithText("Save").assertIsNotEnabled()

            onNodeWithTag(BODY_FIELD).performTextInput("more ")
            click("Save")

            waitUntil(timeoutMillis = TIMEOUT_MS) { notes.get(note.id)?.body == "more body" }
            waitForIdle()
            onNodeWithText("Save").assertIsNotEnabled()
        }

    @Test
    fun theDraftSavesItselfAfterTypingStops() =
        app { notes ->
            val note = notes.create("", "base")
            awaitText("base")
            openNote(note)

            onNodeWithTag(BODY_FIELD).performTextInput("mine ")

            waitUntil(timeoutMillis = TIMEOUT_MS) { notes.get(note.id)?.body == "mine base" }
        }

    @Test
    fun controlSSavesNow() =
        app { notes ->
            val note = notes.create("", "base")
            awaitText("base")
            openNote(note)
            val typedAt = mainClock.currentTime
            onNodeWithTag(BODY_FIELD).performTextInput("mine ")

            onNodeWithTag(BODY_FIELD).performKeyInput {
                withKeyDown(Key.CtrlLeft) { pressKey(Key.S) }
            }

            waitUntil(timeoutMillis = TIMEOUT_MS) { notes.get(note.id)?.body == "mine base" }
            val elapsed = mainClock.currentTime - typedAt
            assertTrue(elapsed < AUTOSAVE_DELAY_MS, "the key saved, not the autosave: $elapsed")
        }

    @Test
    fun aStaleSaveKeepsTheTextOnScreenAndCanBecomeANewNote() =
        app { notes ->
            val note = notes.create("", "base")
            awaitText("base")
            openNote(note)
            onNodeWithTag(BODY_FIELD).performTextClearance()
            onNodeWithTag(BODY_FIELD).performTextInput("my unsaved text")
            database.fromOutside {
                it.notes.edit(note.id, note.revisionId, "", "their text")
            }

            click("Save")

            awaitText(STALE_MESSAGE)
            fieldShows(BODY_FIELD, "my unsaved text")
            assertEquals("their text", notes.get(note.id)?.body)

            click("Save as new note")

            awaitNoText(STALE_MESSAGE)
            fieldShows(BODY_FIELD, "my unsaved text")
            assertEquals(2, notes.list().size)
        }

    @Test
    fun aStaleDraftCanBeDiscardedForTheOtherText() =
        app { notes ->
            val note = notes.create("", "base")
            awaitText("base")
            openNote(note)
            onNodeWithTag(BODY_FIELD).performTextInput("mine ")
            database.fromOutside {
                it.notes.edit(note.id, note.revisionId, "", "their text")
            }
            click("Back")
            awaitText(STALE_MESSAGE)

            click("Discard and load theirs")

            awaitNoText(STALE_MESSAGE)
            fieldShows(BODY_FIELD, "their text")
        }

    @Test
    fun aDraftOfADeletedNoteCanBeDiscarded() =
        app { notes ->
            val note = notes.create("", "base")
            awaitText("base")
            openNote(note)
            onNodeWithTag(BODY_FIELD).performTextInput("mine ")
            database.fromOutside {
                it.notes.setState(note.id, NoteState.TRASHED)
                it.notes.deleteForever(note.id)
            }
            click("Save")
            awaitText(DELETED_MESSAGE)
            fieldShows(BODY_FIELD, "mine base")

            click("Discard")

            awaitText("New note")
        }

    @Test
    fun aDiskErrorKeepsTheTextOnScreenWithAMessage() =
        app { notes ->
            val note = notes.create("", "base")
            awaitText("base")
            openNote(note)
            onNodeWithTag(BODY_FIELD).performTextInput("mine ")
            database.failWrites()

            click("Save")

            awaitText(SAVE_FAILED_MESSAGE, substring = true)
            fieldShows(BODY_FIELD, "mine base")
        }

    @Test
    fun aFailedSaveOnCloseAsksBeforeClosing() {
        var exits = 0
        var viewModel: AppViewModel? = null
        database.open(Dispatchers.IO).use { store ->
            runComposeUiTest {
                setContent {
                    val scope = rememberCoroutineScope()
                    JotterApp(
                        remember {
                            AppViewModel(store.notes, scope, Dispatchers.IO).also {
                                viewModel = it
                            }
                        },
                    )
                }
                val note = store.notes.create("", "base")
                awaitText("base")
                openNote(note)
                onNodeWithTag(BODY_FIELD).performTextInput("mine ")
                database.failWrites()

                viewModel?.requestExit { exits++ }
                awaitText("Your text could not be saved")
                assertEquals(0, exits)
                click("Keep editing")
                awaitNoText("Your text could not be saved")
                fieldShows(BODY_FIELD, "mine base")

                viewModel?.requestExit { exits++ }
                awaitText("Your text could not be saved")
                click("Close without saving")
                waitUntil(timeoutMillis = TIMEOUT_MS) { exits == 1 }
            }
        }
    }

    @Test
    fun aLockedDatabaseShowsAMessageInTheEditor() =
        app { notes ->
            val note = notes.create("", "base")
            awaitText("base")
            openNote(note)
            onNodeWithTag(BODY_FIELD).performTextInput("mine ")

            database.whileLocked {
                click("Back")
                awaitText("Saving…")
                awaitText(BUSY_MESSAGE)
            }

            fieldShows(BODY_FIELD, "mine base")
        }

    @Test
    fun aLockedDatabaseShowsAMessageInTheGrid() =
        app { notes ->
            val note = notes.create("note", "")
            awaitText("note")

            database.whileLocked {
                clickOn(note, "Pin")
                awaitText(BUSY_MESSAGE)
            }

            click("Dismiss")
            awaitNoText(BUSY_MESSAGE)
        }

    @Test
    fun pinningMovesANoteToTheFront() =
        app { notes ->
            val first = notes.create("first", "")
            val second = notes.create("second", "")
            awaitText("first")
            val left = { note: Note -> onNodeWithTag(cardTag(note.id)).getBoundsInRoot().left }
            assertTrue(left(second) < left(first))

            clickOn(first, "Pin")

            awaitText("Pinned")
            waitForIdle()
            assertTrue(left(first) < left(second))

            clickOn(first, "Unpin")
            awaitNoText("Pinned")
        }

    @Test
    fun aTagAddedInTheEditorReachesTheFilter() =
        app { notes ->
            val note = notes.create("note", "text")
            notes.create("other", "")
            awaitText("note")
            openNote(note)

            onNodeWithTag(TAG_FIELD).performTextInput("work")
            click("Add tag")
            awaitText("work ×")
            click("Back")

            awaitText("work (1)")
            click("work (1)")
            awaitNoText("other")

            openNote(note)
            click("work ×")
            awaitNoText("work ×")
            click("Back")

            awaitText("work (0)")
            awaitText("Nothing here.")
            click("work (0)")
            awaitText("other")
        }

    @Test
    fun anInvalidTagShowsAMessage() =
        app { notes ->
            val note = notes.create("note", "text")
            awaitText("note")
            openNote(note)

            click("Add tag")

            awaitText(INVALID_TAG_MESSAGE)
            assertEquals(emptySet(), notes.get(note.id)?.tags)
        }

    @Test
    fun aTagChipFiltersTheGrid() =
        app { notes ->
            notes.create("tagged", "", setOf(TagName.parse("work")))
            notes.create("plain", "")
            awaitText("plain")

            click("work (1)")

            awaitNoText("plain")
            onNodeWithText("tagged").assertExists()

            click("work (1)")
            awaitText("plain")
        }

    @Test
    fun theSelectedTagStaysWhenItsLastNoteIsTrashed() =
        app { notes ->
            val note = notes.create("tagged", "", setOf(TagName.parse("work")))
            notes.create("other", "", setOf(TagName.parse("home")))
            awaitText("work (1)")
            click("work (1)")
            awaitText("tagged")

            clickOn(note, "Trash")

            awaitText("work (0)")
            click("work (0)")
            awaitNoText("work (0)")
        }

    @Test
    fun searchFollowsTyping() =
        app { notes ->
            notes.create("milk", "from the shop")
            notes.create("bread", "")
            awaitText("bread")

            onNodeWithTag(SEARCH_FIELD).performTextInput("m")
            waitForIdle()
            onNodeWithText("bread").assertExists()

            onNodeWithTag(SEARCH_FIELD).performTextInput("i")

            awaitNoText("bread")
            onNodeWithText("milk").assertExists()
        }

    @Test
    fun aNoteTravelsThroughTheArchiveAndTheTrash() =
        app { notes ->
            val note = notes.create("note", "")
            awaitText("note")

            clickOn(note, "Archive")
            awaitText("Nothing here.")
            click("Archive")
            awaitText("note")

            clickOn(note, "Restore")
            awaitText("Nothing here.")
            click("Notes")
            awaitText("note")

            clickOn(note, "Trash")
            awaitText("Nothing here.")
            click("Trash")
            awaitText("note")

            clickOn(note, "Restore")
            awaitText("Nothing here.")
            assertEquals(NoteState.ACTIVE, notes.get(note.id)?.state)
        }

    @Test
    fun deletingForeverAsksFirst() =
        app { notes ->
            val note = notes.create("note", "")
            notes.setState(note.id, NoteState.TRASHED)
            onNodeWithText("Trash").performClick()
            awaitText("note")

            clickOn(note, "Delete forever")
            awaitText("Delete this note forever?")
            click("Cancel")
            awaitNoText("Delete this note forever?")
            assertEquals(NoteState.TRASHED, notes.get(note.id)?.state)

            clickOn(note, "Delete forever")
            awaitText("Delete this note forever?")
            click("Delete")

            awaitText("Nothing here.")
            assertEquals(null, notes.get(note.id))
        }

    /** Remembered handlers must follow the view model, or a click would write to the old one. */
    @Test
    fun theScreensFollowANewViewModel() {
        val other = TestDatabase()
        try {
            database.open(Dispatchers.IO).use { first ->
                other.open(Dispatchers.IO).use { second ->
                    runComposeUiTest {
                        var useSecond by mutableStateOf(false)
                        setContent {
                            val scope = rememberCoroutineScope()
                            val one = remember { AppViewModel(first.notes, scope, Dispatchers.IO) }
                            val two = remember { AppViewModel(second.notes, scope, Dispatchers.IO) }
                            JotterApp(if (useSecond) two else one)
                        }
                        switchBetween(first.notes, second.notes) { useSecond = it }
                    }
                }
            }
        } finally {
            other.delete()
        }
    }

    private fun ComposeUiTest.switchBetween(
        first: NoteService,
        second: NoteService,
        useSecond: (Boolean) -> Unit,
    ) {
        val mine = first.create("mine", "")
        val theirs = second.create("theirs", "")
        val trashed = first.create("gone", "").also { first.setState(it.id, NoteState.TRASHED) }
        second.setState(second.create("gone too", "").id, NoteState.TRASHED)
        awaitText("mine")

        useSecond(true)
        awaitText("theirs")
        clickOn(theirs, "Pin")
        waitUntil(timeoutMillis = TIMEOUT_MS) { second.get(theirs.id)?.isPinned == true }
        assertEquals(false, first.get(mine.id)?.isPinned)

        onAllNodesWithText("Trash")[0].performClick()
        awaitText("gone too")
        useSecond(false)
        awaitText("mine")
        onAllNodesWithText("Trash")[0].performClick()
        awaitText("gone")
        clickOn(trashed, "Restore")
        waitUntil(timeoutMillis = TIMEOUT_MS) { first.get(trashed.id)?.state == NoteState.ACTIVE }
        click("Notes")
        awaitText("mine")

        openNote(mine)
        onNodeWithTag(BODY_FIELD).performTextInput("my text")
        useSecond(true)
        click("Notes")
        awaitText("theirs")
        openNote(theirs)
        useSecond(false)
        waitForIdle()
        fieldShows(BODY_FIELD, "my text")
        click("Back")
        waitUntil(timeoutMillis = TIMEOUT_MS) { first.get(mine.id)?.body == "my text" }
    }

    @Test
    fun aHighlightedSnippetKeepsItsTextAndMarksItsMatches() {
        val text = annotated(Highlighted("a milk b milk", listOf(2..5, 9..12)))

        assertEquals("a milk b milk", text.text)
        assertEquals(listOf(2 to 6, 9 to 13), text.spanStyles.map { it.start to it.end })
    }

    @Test
    fun gainingFocusRefreshes() =
        runComposeUiTest {
            var focused by mutableStateOf(false)
            var refreshes = 0
            setContent { RefreshWhenFocused(focused) { refreshes++ } }
            waitForIdle()
            assertEquals(0, refreshes)

            focused = true
            waitForIdle()
            assertEquals(1, refreshes)

            focused = false
            waitForIdle()
            assertEquals(1, refreshes)
        }
}
