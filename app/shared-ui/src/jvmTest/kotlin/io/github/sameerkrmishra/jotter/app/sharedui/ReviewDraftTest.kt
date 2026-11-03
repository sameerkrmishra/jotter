package io.github.sameerkrmishra.jotter.app.sharedui

import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.storage.MAX_NOTE_CHARS
import io.github.sameerkrmishra.jotter.core.sync.ConflictResolution
import io.github.sameerkrmishra.jotter.core.sync.ConflictResolver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Texts combined by hand: kept on disk, never saved over an edit made meanwhile. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewDraftTest : EditorTest() {
    private val world = SyncWorld()

    @AfterTest
    fun closeWorld() {
        world.close()
    }

    private fun Fixture.review(id: NoteId): ReviewViewModel {
        app.openReview(id)
        settle()
        return assertNotNull(app.review)
    }

    private suspend fun Fixture.conflicted(): Pair<Note, ReviewViewModel> {
        val note = world.conflict(store)
        settle()
        return note to review(note.id)
    }

    private fun ReviewViewModel.conflict() =
        Decision.Conflict(conflictDetails.single().conflict.mergeId)

    private fun ReviewViewModel.shownHead() = conflictDetails.single().shownHeadId

    private fun Fixture.combined(review: ReviewViewModel): CombinedDraft {
        review.combineByHand(review.conflict(), review.shownHead())
        settle()
        return assertNotNull(review.draftFor(review.conflict()))
    }

    /** Edits the note as the editor or the CLI would, on what it shows now. */
    private fun Fixture.editMeanwhile(
        note: NoteId,
        body: String,
    ): Note {
        val shown = assertNotNull(notes.get(note))
        return notes.edit(note, shown.revisionId, shown.title, body).also { settle() }
    }

    @Test
    fun aTextCombinedByHandResolvesTheConflict() =
        test {
            val (note, review) = conflicted()
            val draft = combined(review)
            assertTrue(draft.hasMarkers)
            assertEquals(review.conflictDetails.single().markedBody, draft.body)

            draft.body = "eggs\noat milk\nsoy milk\nbread"
            draft.title = "Shopping"
            assertFalse(draft.hasMarkers)
            review.saveDraft(draft)
            settle()

            assertEquals("eggs\noat milk\nsoy milk\nbread", notes.get(note.id)?.body)
            assertEquals("Shopping", notes.get(note.id)?.title)
            assertEquals(emptyMap(), review.drafts)
            assertEquals(emptyList(), conflicts.drafts())
            assertTrue(review.isDone)
        }

    @Test
    fun aDraftIsKeptOnDiskAndComesBackInANewApp() =
        test {
            val (note, review) = conflicted()
            val draft = combined(review)
            draft.body = "typed before a crash"
            settle()
            assertEquals("typed before a crash", conflicts.drafts().single().body)
            assertEquals(
                "$CONFLICT_LABEL, $UNSAVED_LABEL",
                app.attention.byNote
                    .getValue(note.id)
                    .label,
            )

            val restarted =
                AppViewModel(
                    notes,
                    ConflictResolver(store),
                    scope,
                    UnconfinedTestDispatcher(testScheduler),
                )
            restarted.openReview(note.id)
            settle()

            val back = assertNotNull(assertNotNull(restarted.review).drafts.values.single())
            assertEquals("typed before a crash", back.body)
            assertEquals(review.shownHead(), back.baseId)
            restarted.closeReview()
            settle()
        }

    @Test
    fun leavingTheReviewOrTheAppKeepsTheDraftAtOnce() =
        test {
            val (note, review) = conflicted()
            val draft = combined(review)
            draft.body = "typed just now"

            app.closeReview()
            testScheduler.runCurrent()

            assertNull(app.review)
            assertEquals("typed just now", conflicts.drafts().single().body)

            val again = review(note.id)
            val restored = again.drafts.values.single()
            restored.title = "and a title"
            var exited = false
            app.requestExit { exited = true }
            testScheduler.runCurrent()

            assertTrue(exited)
            assertEquals("and a title", conflicts.drafts().single().title)
        }

    @Test
    fun aDraftThatCannotBeKeptHoldsTheReviewOpen() =
        test {
            val (_, review) = conflicted()
            val draft = combined(review)
            draft.body = "unsaved"

            database.whileLocked {
                app.closeReview()
                testScheduler.runCurrent()
            }

            assertSame(review, app.review)
            assertTrue(review.message.orEmpty().startsWith(DRAFT_NOT_KEPT_MESSAGE))
            var exited = false
            database.whileLocked {
                app.requestExit { exited = true }
                testScheduler.runCurrent()
            }
            assertFalse(exited)
            assertTrue(app.pendingExit != null)
        }

    @Test
    fun aDraftCanBeDiscarded() =
        test {
            val (_, review) = conflicted()
            val draft = combined(review)

            review.discardDraft(draft)
            settle()

            assertEquals(emptyMap(), review.drafts)
            assertEquals(emptyList(), conflicts.drafts())
            assertEquals(1, review.conflictDetails.size)
        }

    @Test
    fun aDraftWhoseConflictWasResolvedElsewhereStaysAndCanBeSaved() =
        test {
            val (note, review) = conflicted()
            val draft = combined(review)
            draft.body = "my combination"
            conflicts.resolve(
                review.conflict().mergeId,
                ConflictResolution.KeepShown,
                review.shownHead(),
            )
            settle()

            assertEquals(listOf(draft), review.orphans)
            assertFalse(review.isDone)
            assertEquals(
                UNSAVED_LABEL,
                app.attention.byNote
                    .getValue(note.id)
                    .label,
            )
            review.saveDraft(draft)
            settle()
            assertEquals(ALREADY_RESOLVED_MESSAGE, review.message)
            assertEquals(listOf(draft), review.orphans)

            review.saveDraftAsNewNote(draft)
            settle()

            assertEquals(SAVED_AS_NEW_NOTE, review.notice)
            assertTrue(notes.list().any { it.body == "my combination" })
            assertEquals(emptyList(), review.orphans)
            assertEquals(emptyList(), conflicts.drafts())
            assertTrue(review.isDone)
            assertEquals(emptyList(), app.attention.entries)
        }

    @Test
    fun aCombinedTextTooLongToSaveStays() =
        test {
            val (note, review) = conflicted()
            val draft = combined(review)
            draft.body = "x".repeat(MAX_NOTE_CHARS + 1)

            review.saveDraft(draft)
            settle()

            assertEquals(COMBINED_TOO_LARGE_MESSAGE, review.message)
            assertSame(draft, review.draftFor(review.conflict()))
            assertEquals(1, review.conflictDetails.size)
            assertTrue(
                notes
                    .get(note.id)
                    ?.body
                    .orEmpty()
                    .length < MAX_NOTE_CHARS,
            )
        }

    @Test
    fun aWaitingEditCanBeCombinedByHandButNotOverAnEditMadeMeanwhile() =
        test {
            val note = world.waiting(store)
            settle()
            val review = review(note.id)
            val waiting = review.waiting.single()
            val shown = assertNotNull(review.note)
            val decision = Decision.Waiting(waiting.revisionId)

            review.combineByHand(decision, shown.revisionId)
            settle()
            val draft = assertNotNull(review.draftFor(decision))
            assertEquals("Plans", draft.title) // The same on both sides: no markers.
            assertTrue(draft.body.contains("<<<<<<< shown\n${shown.body}\n=======\n"))
            assertTrue(draft.body.contains("${waiting.body}\n>>>>>>> other device"))
            draft.body = "both"
            editMeanwhile(note.id, "NEW LINE")

            review.saveDraft(draft)
            settle()

            assertEquals("NEW LINE", notes.get(note.id)?.body)
            assertTrue(draft.isOutdated)
            assertEquals("both", draft.body)
            draft.body = "both and NEW LINE"
            review.saveDraft(draft)
            settle()

            assertEquals("both and NEW LINE", notes.get(note.id)?.body)
            assertTrue(review.isDone)
        }
}
