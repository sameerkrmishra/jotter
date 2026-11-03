@file:OptIn(ExperimentalTestApi::class)

package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import io.github.sameerkrmishra.jotter.core.sync.ConflictResolution
import io.github.sameerkrmishra.jotter.core.sync.ConflictResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val TIMEOUT_MS = 10_000L

/**
 * The real screens on conflicts, waiting edits and set-aside text that core:sync made between two
 * real devices. Writes run on [Dispatchers.IO] as in the app, so the tests wait for their result.
 * Each test checks that every text stays on screen until the user decides.
 */
@Suppress("InjectDispatcher") // The tests stand in for a composition root.
class ReviewScreenTest {
    private val database = TestDatabase()
    private val world = SyncWorld()

    @AfterTest
    fun cleanUp() {
        world.close()
        database.delete()
    }

    private fun app(block: ComposeUiTest.(NoteStore) -> Unit) =
        database.open(Dispatchers.IO).use { store ->
            runComposeUiTest {
                setContent {
                    val scope = rememberCoroutineScope()
                    JotterApp(
                        remember {
                            AppViewModel(
                                store.notes,
                                ConflictResolver(store),
                                scope,
                                Dispatchers.IO,
                            )
                        },
                    )
                }
                block(store)
            }
        }

    private fun ComposeUiTest.await(
        text: String,
        substring: Boolean = false,
    ) = waitUntil(timeoutMillis = TIMEOUT_MS) {
        onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    private fun ComposeUiTest.awaitNo(text: String) =
        waitUntil(timeoutMillis = TIMEOUT_MS) {
            onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        }

    // The review scrolls, and a button may be below the window. The grid does not.
    private fun ComposeUiTest.click(text: String) {
        await(text)
        val node = onNodeWithText(text)
        val scrolls = hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy))
        if (scrolls.matches(node.fetchSemanticsNode())) node.performScrollTo()
        node.performClick()
    }

    private fun ComposeUiTest.shows(
        text: String,
        substring: Boolean = true,
    ) = assertTrue(
        onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty(),
        "\"$text\" is on screen",
    )

    /** Goes from the grid to the review of the one note that needs attention. */
    private fun ComposeUiTest.reviewFromTheList() {
        await("$NEEDS_ATTENTION (1)")
        click("$NEEDS_ATTENTION (1)")
        await("Review")
        click("Review")
        await("Back")
    }

    private fun ComposeUiTest.fieldShows(
        tag: String,
        text: String,
    ) = onNodeWithTag(tag).assert(
        SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text)),
    )

    private fun conflict(store: NoteStore): Note = runBlocking { world.conflict(store) }

    @Test
    fun aConflictIsMarkedListedAndResolvedByKeepingTheOtherVersion() =
        app { store ->
            val note = conflict(store)
            await("Needs attention: $CONFLICT_LABEL")
            onNodeWithTag(attentionTag(note.id), useUnmergedTree = true).assertExists()

            reviewFromTheList()

            await(CONFLICT_HEADING)
            shows(CONFLICT_EXPLANATION)
            shows("oat milk")
            shows("soy milk")
            shows(SHOWN_VERSION)
            shows(OTHER_VERSION)
            assertEquals(1, onAllNodesWithTag(DIFFERS_REGION).fetchSemanticsNodes().size)
            val other = ConflictResolver(store).open().single().losingBody

            click(KEEP_OTHER)

            await(NOTHING_TO_REVIEW)
            assertEquals(other, store.notes.get(note.id)?.body)
            click("Back")
            await(NOTHING_NEEDS_ATTENTION)
            click("Notes")
            await("Groceries")
            onNodeWithTag(attentionTag(note.id), useUnmergedTree = true).assertDoesNotExist()
            awaitNo("$NEEDS_ATTENTION (0)")
        }

    @Test
    fun theEditorLeadsToTheReviewAndBackToTheResolvedText() =
        app { store ->
            val note = conflict(store)
            await("Groceries")
            onNodeWithTag(cardTag(note.id)).performSemanticsAction(SemanticsActions.OnClick)
            await("This note needs attention: $CONFLICT_LABEL.")
            click("Review")
            await(CONFLICT_HEADING)

            click(COMBINE)
            await(MARKERS_HINT)
            val marked =
                ConflictResolver(store).details(
                    ConflictResolver(store).open().single().mergeId,
                )
            fieldShows(COMBINED_BODY_FIELD, marked?.markedBody.orEmpty())
            shows("oat milk")
            shows("soy milk")
            onNodeWithTag(COMBINED_BODY_FIELD).performTextClearance()
            onNodeWithTag(COMBINED_BODY_FIELD).performTextInput("eggs\nboth milks\nbread")
            awaitNo(MARKERS_HINT)
            click(SAVE_COMBINED)

            await(NOTHING_TO_REVIEW)
            click("Back")
            await("Title")
            fieldShows(BODY_FIELD, "eggs\nboth milks\nbread")
            awaitNo("This note needs attention: $CONFLICT_LABEL.")
        }

    @Test
    fun keepingTheShownVersionKeepsTheText() =
        app { store ->
            val note = conflict(store)
            val shown = store.notes.get(note.id)?.body
            reviewFromTheList()

            click(KEEP_SHOWN)

            await(NOTHING_TO_REVIEW)
            assertEquals(shown, store.notes.get(note.id)?.body)
            assertEquals(emptyList(), ConflictResolver(store).open())
        }

    @Test
    fun keepingBothMakesTwoNotes() =
        app { store ->
            conflict(store)
            reviewFromTheList()

            click(KEEP_BOTH)

            await(NOTHING_TO_REVIEW)
            assertEquals(
                setOf("eggs\noat milk\nbread", "eggs\nsoy milk\nbread"),
                store.notes
                    .list()
                    .map { it.body }
                    .toSet(),
            )
        }

    @Test
    fun aCombinedTextOutlivesTheReviewAndIsDiscardedOnlyAfterAsking() =
        app { store ->
            conflict(store)
            reviewFromTheList()
            click(COMBINE)
            await(SAVE_COMBINED)
            onNodeWithTag(COMBINED_TITLE_FIELD).performTextInput("My ")

            click("Back")
            await(NEEDS_ATTENTION, substring = true)
            await("Needs attention: $CONFLICT_LABEL, $UNSAVED_LABEL")
            click("Review")
            await(SAVE_COMBINED)
            fieldShows(COMBINED_TITLE_FIELD, "My Groceries")

            click(DISCARD)
            await(DISCARD_DRAFT_TITLE)
            click("Keep it")
            awaitNo(DISCARD_DRAFT_TITLE)
            fieldShows(COMBINED_TITLE_FIELD, "My Groceries")
            click(DISCARD)
            await(DISCARD_DRAFT_TITLE)
            onNode(hasText(DISCARD) and hasAnyAncestor(isDialog())).performClick()

            awaitNo(SAVE_COMBINED)
            assertEquals(emptyList(), ConflictResolver(store).drafts())
            assertEquals(1, ConflictResolver(store).open().size)
        }

    /** The reviewer's case, on screen: an edit saved meanwhile shows beside the kept text. */
    @Test
    fun aCombinedTextIsNotSavedOverAnEditMadeMeanwhile() =
        app { store ->
            val note = conflict(store)
            reviewFromTheList()
            click(COMBINE)
            await(SAVE_COMBINED)
            onNodeWithTag(COMBINED_BODY_FIELD).performTextClearance()
            onNodeWithTag(COMBINED_BODY_FIELD).performTextInput("my combination")
            val shown = assertNotNull(store.notes.get(note.id))
            store.notes.edit(note.id, shown.revisionId, shown.title, "eggs\nNEW LINE")
            await("NEW LINE", substring = true)

            click(SAVE_COMBINED)

            await(OUTDATED_HINT)
            fieldShows(COMBINED_BODY_FIELD, "my combination")
            assertEquals("eggs\nNEW LINE", store.notes.get(note.id)?.body)
            onNodeWithTag(COMBINED_BODY_FIELD).performTextInput(" and NEW LINE")
            click(SAVE_COMBINED)

            await(NOTHING_TO_REVIEW)
            assertEquals("my combination and NEW LINE", store.notes.get(note.id)?.body)
        }

    @Test
    fun aCombinedTextStaysWhenItsConflictIsResolvedElsewhere() =
        app { store ->
            conflict(store)
            reviewFromTheList()
            click(COMBINE)
            await(SAVE_COMBINED)
            onNodeWithTag(COMBINED_BODY_FIELD).performTextClearance()
            onNodeWithTag(COMBINED_BODY_FIELD).performTextInput("my combination")
            waitUntil(timeoutMillis = TIMEOUT_MS) {
                ConflictResolver(store).drafts().singleOrNull()?.body == "my combination"
            }
            val resolver = ConflictResolver(store)
            val details = assertNotNull(resolver.details(resolver.open().single().mergeId))
            resolver.resolve(
                details.conflict.mergeId,
                ConflictResolution.KeepShown,
                details.shownHeadId,
            )

            await(ORPHAN_HEADING)
            shows(ORPHAN_EXPLANATION)
            shows("my combination")
            awaitNo(NOTHING_TO_REVIEW)
            click(SAVE_AS_NEW)

            await(NOTHING_TO_REVIEW)
            assertTrue(store.notes.list().any { it.body == "my combination" })
            assertEquals(emptyList(), resolver.drafts())
        }

    @Test
    fun aTextThatLooksLikeMarkersShowsWithoutRegions() =
        app { store ->
            runBlocking { world.conflict(store, mine = "=======") }
            reviewFromTheList()

            await(CONFLICT_HEADING)
            shows("<<<<<<< shown")
            shows("soy milk")
            assertEquals(0, onAllNodesWithTag(DIFFERS_REGION).fetchSemanticsNodes().size)
            click(COMBINE)
            await(SAVE_COMBINED)
            click("Back")
            await(NEEDS_ATTENTION, substring = true)
        }

    @Test
    fun aConflictingTitleAndARemovedLineShowAsRegions() =
        app { store ->
            val note = store.notes.create("Shopping", "eggs\nmilk\nbread")
            runBlocking { world.exchange(store) }
            store.notes.edit(note.id, note.revisionId, "My shopping", "eggs\nbread")
            world.there.notes.edit(note.id, note.revisionId, "Their shopping", "eggs\nsoy\nbread")
            runBlocking { world.exchange(store) }
            reviewFromTheList()

            await(CONFLICT_HEADING)
            assertEquals(2, onAllNodesWithTag(DIFFERS_REGION).fetchSemanticsNodes().size)
            shows("My shopping")
            shows("Their shopping")
            shows("(nothing)", substring = false)
        }

    @Test
    fun anEmptiedNoteShowsThatItIsEmpty() =
        app { store ->
            val note = store.notes.create("List", "words")
            runBlocking { world.exchange(store) }
            store.notes.edit(note.id, note.revisionId, "List", "")
            world.there.notes.edit(note.id, note.revisionId, "List", "other words")
            runBlocking { world.exchange(store) }
            reviewFromTheList()

            await(CONFLICT_HEADING)
            shows("other words")
            shows("(nothing)", substring = false)
        }

    @Test
    fun aWaitingEditCanBeTakenInstead() =
        app { store ->
            val note = runBlocking { world.waiting(store) }
            await("Needs attention: $WAITING_LABEL")
            reviewFromTheList()

            await(WAITING_HEADING)
            shows(WAITING_EXPLANATION)
            shows("my edit")
            shows("their second edit")
            val waiting = ConflictResolver(store).hidden().single().body

            click(USE_WAITING)

            await(NOTHING_TO_REVIEW)
            assertEquals(waiting, store.notes.get(note.id)?.body)
        }

    @Test
    fun anEditWaitingForAConflictShowsItsText() =
        app { store ->
            val note = store.notes.create("Plans", "start")
            runBlocking { world.exchange(store) }
            store.notes.edit(note.id, note.revisionId, "Plans", "mine")
            world.there.notes.edit(note.id, note.revisionId, "Plans", "theirs")
            runBlocking {
                world.forge(note.id, note.revisionId, "Plans", "a third device")
                world.exchange(store)
            }
            reviewFromTheList()

            await(QUEUED_HEADING)
            shows(QUEUED_EXPLANATION)
            listOf("mine", "theirs", "a third device").forEach { shows(it, substring = false) }
        }

    @Test
    fun textSetAsideIsExplainedAndCanBeSavedAsANewNote() =
        app { store ->
            val note = store.notes.create("Plans", "start")
            runBlocking {
                world.forge(note.id, note.revisionId, "Plans", "from a bad clock", millis = 1)
                world.forge(note.id, note.revisionId, "Plans", "damaged", state = "SHREDDED")
                world.forge(note.id, note.revisionId, "", "", state = "SHREDDED")
                world.sync(store)
            }
            await("Needs attention: $SET_ASIDE_LABEL")
            reviewFromTheList()

            await(SET_ASIDE_HEADING)
            shows(BAD_HISTORY_EXPLANATION)
            shows(MALFORMED_EXPLANATION)
            shows("from a bad clock")
            shows("damaged")
            shows("(nothing)", substring = false)
            val badClock =
                ConflictResolver(
                    store,
                ).invalid().single { it.body == "from a bad clock" }

            onNode(hasText(SAVE_AS_NEW) and hasAnyAncestor(hasTestTag(setAsideTag(badClock))))
                .performScrollTo()
                .performClick()

            await(SAVED_AS_NEW_NOTE)
            assertEquals(1, store.notes.list().count { it.body == "from a bad clock" })
            awaitNo(BAD_HISTORY_EXPLANATION) // Saved, so no longer listed: no second copy.
            click("OK")
            awaitNo(SAVED_AS_NEW_NOTE)

            val damaged = ConflictResolver(store).invalid().single { it.body == "damaged" }
            onNode(hasText(DISCARD) and hasAnyAncestor(hasTestTag(setAsideTag(damaged))))
                .performScrollTo()
                .performClick()
            await(DISCARD_SET_ASIDE_TITLE)
            shows(DISCARD_SET_ASIDE_TEXT)
            onNode(hasText(DISCARD) and hasAnyAncestor(isDialog())).performClick()

            awaitNo("damaged")
            assertEquals(1, ConflictResolver(store).invalid().size)
            assertEquals(
                listOf("start", "from a bad clock"),
                store.notes
                    .list()
                    .map {
                        it.body
                    }.sorted()
                    .reversed(),
            )
        }

    @Test
    fun aNoteWithNoTextThatCountsIsListedByItsText() =
        app { store ->
            val note = store.notes.create("", "the first words")
            runBlocking {
                world.forge(note.id, null, "", "another text", id = note.revisionId)
                world.sync(store)
            }
            awaitNo("the first words")
            reviewFromTheList()

            await(COLLISION_EXPLANATION)
            shows("the first words")
            shows("another text")
        }

    @Test
    fun aFailedDecisionSaysWhyAndChangesNothing() =
        app { store ->
            conflict(store)
            reviewFromTheList()
            await(CONFLICT_HEADING)
            database.failWrites()

            click(KEEP_SHOWN)

            await(RESOLVE_FAILED_MESSAGE, substring = true)
            assertEquals(1, ConflictResolver(store).open().size)
            click("Dismiss")
            awaitNo("disk full")
            database.allowWrites()
        }

    @Test
    fun aFailedReadOfWhatNeedsAttentionSaysSo() {
        var viewModel: AppViewModel? = null
        database.open(Dispatchers.IO).use { store ->
            runComposeUiTest {
                setContent {
                    val scope = rememberCoroutineScope()
                    JotterApp(
                        remember {
                            AppViewModel(
                                store.notes,
                                ConflictResolver(store),
                                scope,
                                Dispatchers.IO,
                            ).also { viewModel = it }
                        },
                    )
                }
                conflict(store)
                click("$NEEDS_ATTENTION (1)")
                await("Groceries")
                store.close()

                viewModel?.refresh()

                await(ATTENTION_READ_FAILED)
                shows("Groceries") // What was read stays.
            }
        }
    }

    /** The exit criterion of the conflict UI: a conflict answered here reaches the other device. */
    @Test
    fun twoDevicesConvergeOnWhatTheUserCombined() =
        app { store ->
            val note = conflict(store)
            reviewFromTheList()
            click(COMBINE)
            await(SAVE_COMBINED)
            onNodeWithTag(COMBINED_BODY_FIELD).performTextClearance()
            onNodeWithTag(COMBINED_BODY_FIELD).performTextInput("eggs\noat milk\nsoy milk\nbread")
            click(SAVE_COMBINED)
            await(NOTHING_TO_REVIEW)

            runBlocking { world.exchange(store) }

            val here = store.notes.get(note.id)
            assertEquals("eggs\noat milk\nsoy milk\nbread", here?.body)
            assertEquals(here, world.there.notes.get(note.id))
            assertEquals(emptyList(), ConflictResolver(store).open())
            assertEquals(emptyList(), ConflictResolver(world.there).open())
            assertEquals(emptyList(), ConflictResolver(world.there).hidden())
            click("Back")
            await(NOTHING_NEEDS_ATTENTION)
        }
}
