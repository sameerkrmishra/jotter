package io.github.sameerkrmishra.jotter.app.sharedui

import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteId
import io.github.sameerkrmishra.jotter.core.model.RevisionId
import io.github.sameerkrmishra.jotter.core.storage.HiddenHead
import io.github.sameerkrmishra.jotter.core.storage.HiddenReason
import io.github.sameerkrmishra.jotter.core.storage.InvalidReason
import io.github.sameerkrmishra.jotter.core.storage.InvalidRevision
import io.github.sameerkrmishra.jotter.core.storage.OpenConflict
import io.github.sameerkrmishra.jotter.core.storage.ResolutionDraft
import io.github.sameerkrmishra.jotter.core.sync.ConflictResolution
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The review of a note's sync problems, on states that core:sync itself made. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelTest : EditorTest() {
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

    /** Answers the one conflict on what the review shows now. */
    private fun ReviewViewModel.answer(resolution: ConflictResolution) =
        decide(conflict(), resolution, shownHead())

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
    fun aConflictShowsBothVersionsAndTheTextWithMarkers() =
        test {
            val (note, review) = conflicted()
            val details = review.conflictDetails.single()
            assertEquals(
                setOf("eggs\noat milk\nbread", "eggs\nsoy milk\nbread"),
                setOf(details.shownBody, details.conflict.losingBody),
            )
            assertTrue(details.markedBody.contains("<<<<<<< shown"))
            assertEquals(details.shownBody, review.note?.body)
            assertEquals(review.note?.revisionId, details.shownHeadId)
            assertEquals(
                CONFLICT_LABEL,
                app.attention.byNote
                    .getValue(note.id)
                    .label,
            )
            assertEquals(
                "Groceries",
                app.attention.entries
                    .single()
                    .title,
            )
            assertFalse(review.isDone)
        }

    @Test
    fun keepingTheShownVersionClosesTheConflict() =
        test {
            val (note, review) = conflicted()
            val shown = review.conflictDetails.single().shownBody

            review.answer(ConflictResolution.KeepShown)
            settle()

            assertEquals(shown, notes.get(note.id)?.body)
            assertTrue(review.isDone)
            assertNull(review.message)
            assertEquals(emptyList(), app.attention.entries)
        }

    @Test
    fun keepingTheOtherVersionReplacesTheText() =
        test {
            val (note, review) = conflicted()
            val other =
                review.conflictDetails
                    .single()
                    .conflict.losingBody

            review.answer(ConflictResolution.KeepLosing)
            settle()

            assertEquals(other, notes.get(note.id)?.body)
            assertTrue(review.isDone)
        }

    @Test
    fun keepingBothMakesANoteOfTheOtherVersion() =
        test {
            val (note, review) = conflicted()
            val details = review.conflictDetails.single()

            review.answer(ConflictResolution.KeepBoth)
            settle()

            assertEquals(details.shownBody, notes.get(note.id)?.body)
            assertEquals(
                setOf(details.shownBody, details.conflict.losingBody),
                notes.list().map { it.body }.toSet(),
            )
            assertTrue(review.isDone)
        }

    @Test
    fun keepingTheOtherVersionAfterAnEditMeanwhileIsRefused() =
        test {
            val (note, review) = conflicted()
            val seen = review.shownHead()
            editMeanwhile(note.id, "NEW LINE")

            review.decide(review.conflict(), ConflictResolution.KeepLosing, seen)
            settle()

            assertEquals(SHOWN_CHANGED_MESSAGE, review.message)
            assertEquals("NEW LINE", notes.get(note.id)?.body)
            assertEquals("NEW LINE", review.conflictDetails.single().shownBody)
        }

    /** The reviewer's case: an edit saved while the user combines must not vanish. */
    @Test
    fun aCombinedTextIsNotSavedOverAnEditMadeMeanwhile() =
        test {
            val (note, review) = conflicted()
            val draft = combined(review)
            draft.body = "my combination"
            val newer = editMeanwhile(note.id, "eggs\nNEW LINE")

            review.saveDraft(draft)
            settle()

            assertEquals("eggs\nNEW LINE", notes.get(note.id)?.body)
            assertEquals(1, review.conflictDetails.size)
            assertEquals("eggs\nNEW LINE", review.conflictDetails.single().shownBody)
            assertSame(draft, review.draftFor(review.conflict()))
            assertTrue(draft.isOutdated)
            assertEquals("my combination", draft.body)
            assertEquals(newer.revisionId, draft.baseId)
            assertEquals(newer.revisionId, conflicts.drafts().single().baseId)
            assertEquals(SHOWN_CHANGED_MESSAGE, review.message)

            draft.body = "eggs\nNEW LINE\nmy combination"
            review.saveDraft(draft)
            settle()

            assertEquals("eggs\nNEW LINE\nmy combination", notes.get(note.id)?.body)
            assertTrue(review.isDone)
        }

    @Test
    fun aFailedWriteChangesNothingAndSaysWhy() =
        test {
            val (_, review) = conflicted()
            database.failWrites()

            review.answer(ConflictResolution.KeepShown)
            settle()

            assertTrue(review.message.orEmpty().startsWith(RESOLVE_FAILED_MESSAGE))
            assertTrue(review.message.orEmpty().contains("disk full"))
            assertEquals(1, review.conflictDetails.size)
            database.allowWrites()
            review.dismissMessage()
            assertNull(review.message)
        }

    @Test
    fun aLockedDatabaseChangesNothingAndSaysSo() =
        test {
            val (_, review) = conflicted()

            database.whileLocked {
                review.answer(ConflictResolution.KeepShown)
                settle()
            }

            assertEquals(BUSY_MESSAGE, review.message)
            assertEquals(1, conflicts.open().size)
        }

    @Test
    fun closingTheReviewDuringADecisionShowsNoFailure() =
        test {
            val note = world.conflict(store)
            settle()
            val io = HeldDispatcher()
            val held = AppViewModel(notes, conflicts, scope, io)
            held.openReview(note.id)
            io.release()
            settle()
            val review = assertNotNull(held.review)
            io.release()
            settle()

            review.answer(ConflictResolution.KeepShown)
            review.close()
            io.release()
            settle()

            assertNull(review.message)
            assertFalse(review.isWorking)
            assertEquals(1, conflicts.open().size)
        }

    @Test
    fun oneDecisionAtATime() =
        test {
            val note = world.conflict(store)
            settle()
            val io = HeldDispatcher()
            val held = AppViewModel(notes, conflicts, scope, io)
            held.openReview(note.id)
            io.release()
            settle()
            val review = assertNotNull(held.review)
            io.release()
            settle()
            val decision = review.conflict()
            val head = review.shownHead()

            review.decide(decision, ConflictResolution.KeepShown, head)
            assertTrue(review.isWorking)
            review.decide(decision, ConflictResolution.KeepLosing, head)
            repeat(3) {
                io.release()
                settle()
            }

            assertFalse(review.isWorking)
            assertNull(review.message) // The second click did nothing: no "already resolved".
            assertEquals(review.note?.body, notes.get(note.id)?.body)
        }

    @Test
    fun aWaitingEditShowsWithItsText() =
        test {
            val note = world.waiting(store)
            settle()
            val review = review(note.id)

            val waiting = review.waiting.single()
            assertEquals(HiddenReason.MISSING_ANCESTOR, waiting.reason)
            assertEquals(
                setOf("my edit", "their second edit"),
                setOf(waiting.body, review.note?.body),
            )
            assertEquals(
                WAITING_LABEL,
                app.attention.byNote
                    .getValue(note.id)
                    .label,
            )
        }

    private suspend fun Fixture.settled(resolution: ConflictResolution): Pair<Note, String> {
        val note = world.waiting(store)
        settle()
        val review = review(note.id)
        val waiting = review.waiting.single()
        review.decide(
            Decision.Waiting(waiting.revisionId),
            resolution,
            assertNotNull(review.note).revisionId,
        )
        settle()
        assertTrue(review.isDone)
        return note to waiting.body
    }

    @Test
    fun aWaitingEditCanBeLeftOut() =
        test {
            val (note, waiting) = settled(ConflictResolution.KeepShown)
            assertTrue(notes.get(note.id)?.body != waiting)
        }

    @Test
    fun aWaitingEditCanBeTakenInstead() =
        test {
            val (note, waiting) = settled(ConflictResolution.KeepLosing)
            assertEquals(waiting, notes.get(note.id)?.body)
        }

    @Test
    fun aWaitingEditCanBecomeANoteOfItsOwn() =
        test {
            val (_, waiting) = settled(ConflictResolution.KeepBoth)
            assertTrue(notes.list().any { it.body == waiting })
            assertEquals(2, notes.list().size)
        }

    /** Three devices change one line, so one edit waits for the conflict of the other two. */
    private suspend fun Fixture.queued(): Pair<Note, ReviewViewModel> {
        val note = notes.create("Plans", "start")
        world.exchange(store)
        notes.edit(note.id, note.revisionId, "Plans", "mine")
        world.there.notes.edit(note.id, note.revisionId, "Plans", "theirs")
        world.forge(note.id, note.revisionId, "Plans", "a third device")
        world.exchange(store)
        settle()
        return note to review(note.id)
    }

    @Test
    fun anEditWaitingForAConflictCannotBeSettled() =
        test {
            val (note, review) = queued()
            val queued = review.waiting.single { it.reason == HiddenReason.OPEN_CONFLICT }
            assertEquals(
                CONFLICT_LABEL,
                app.attention.byNote
                    .getValue(note.id)
                    .label,
            )

            review.decide(
                Decision.Waiting(queued.revisionId),
                ConflictResolution.KeepShown,
                assertNotNull(review.note).revisionId,
            )
            settle()

            assertEquals(WAITS_FOR_CONFLICT_MESSAGE, review.message)
            assertTrue(review.waiting.isNotEmpty())
        }

    @Test
    fun aWaitingEditSettledElsewhereSaysSo() =
        test {
            val note = world.waiting(store)
            settle()
            val review = review(note.id)
            val head = review.waiting.single().revisionId
            val shown = assertNotNull(review.note).revisionId
            conflicts.settleHidden(head, ConflictResolution.KeepShown, shown)

            review.decide(Decision.Waiting(head), ConflictResolution.KeepLosing, shown)
            settle()

            assertEquals(ALREADY_RESOLVED_MESSAGE, review.message)
        }

    @Test
    fun aConflictResolvedElsewhereSaysSo() =
        test {
            val (_, review) = conflicted()
            val decision = review.conflict()
            val head = review.shownHead()
            conflicts.resolve(decision.mergeId, ConflictResolution.KeepShown, head)

            review.decide(decision, ConflictResolution.KeepLosing, head)
            settle()

            assertEquals(ALREADY_RESOLVED_MESSAGE, review.message)
        }

    @Test
    fun textThatDoesNotFitTheHistoryIsSetAsideAndCanBeSavedOnce() =
        test {
            val note = notes.create("Plans", "start")
            world.forge(note.id, note.revisionId, "Plans", "from a bad clock", millis = 1)
            world.sync(store)
            settle()
            val review = review(note.id)

            val text = review.setAside.single()
            assertEquals(InvalidReason.BAD_HISTORY, text.reason)
            assertEquals("from a bad clock", text.body)
            assertEquals("start", review.note?.body)
            assertEquals(
                SET_ASIDE_LABEL,
                app.attention.byNote
                    .getValue(note.id)
                    .label,
            )

            review.saveAsNewNote(text)
            settle()
            review.saveAsNewNote(text) // A second press, from a screen that had not caught up.
            settle()

            assertEquals(SAVED_AS_NEW_NOTE, review.notice)
            assertEquals(1, notes.list().count { it.body == "from a bad clock" })
            assertEquals(emptyList(), review.setAside)
            assertEquals(emptyList(), app.attention.entries)
            review.dismissNotice()
            assertNull(review.notice)
        }

    @Test
    fun damagedTextIsSetAsideAndCanBeDismissed() =
        test {
            val note = notes.create("Plans", "start")
            world.forge(note.id, note.revisionId, "Plans", "damaged", state = "SHREDDED")
            world.sync(store)
            settle()
            val review = review(note.id)
            val text = review.setAside.single()
            assertEquals(InvalidReason.MALFORMED, text.reason)
            assertEquals("damaged", text.body)

            review.dismiss(text)
            settle()

            assertTrue(review.isDone)
            assertEquals(emptyList(), app.attention.entries)
            assertEquals(listOf("start"), notes.list().map { it.body })
        }

    @Test
    fun aNoteWithNoTextThatCountsIsStillReviewed() =
        test {
            val note = notes.create("", "the first words")
            world.forge(note.id, null, "Twin", "another text", id = note.revisionId)
            world.sync(store)
            settle()
            val review = review(note.id)

            assertNull(review.note)
            assertEquals(
                setOf("the first words", "another text"),
                review.setAside.map { it.body }.toSet(),
            )
            assertTrue(review.setAside.all { it.reason == InvalidReason.ID_COLLISION })
            assertEquals(emptyList(), notes.list())
            assertEquals(
                "the first words",
                app.attention.entries
                    .single()
                    .title,
            )
        }

    @Test
    fun aFailedReadKeepsWhatShowedAndSaysSo() =
        test {
            val (_, review) = conflicted()
            store.close()

            app.refresh()
            settle()

            assertEquals(READ_FAILED_MESSAGE, review.message)
            assertTrue(app.attention.failed)
            assertEquals(1, review.conflictDetails.size)
        }

    @Test
    fun openingTheReviewSavesTheEditorFirst() =
        test {
            val note = world.conflict(store)
            settle()
            val editor = open(notes.get(note.id))
            editor.body = "typed before the review"

            app.openReview(note.id)
            settle()

            val review = assertNotNull(app.review)
            assertEquals("typed before the review", review.conflictDetails.single().shownBody)
            app.openReview(note.id) // Another click replaces it.
            settle()
            assertTrue(review !== app.review)
            app.closeReview()
            settle()
            assertNull(app.review)
            app.closeReview() // A second Back finds nothing to close.
            assertSame(editor, app.editor)
        }

    @Test
    fun theReviewWaitsWhileTheEditorCannotSave() =
        test {
            val note = world.conflict(store)
            settle()
            val editor = open(notes.get(note.id))
            editor.body = "typed"
            database.failWrites()

            app.openReview(note.id)
            settle()

            assertNull(app.review)
            assertNotNull(editor.message)
            database.allowWrites()
        }

    @Test
    fun summariesPreferATitleThenAFirstLine() {
        assertEquals("Title", summaryOf(listOf("  Title " to "body")))
        assertEquals("first", summaryOf(listOf("" to "\n  \n first \nsecond")))
        assertEquals("next", summaryOf(listOf("" to " ", "next" to "")))
        assertEquals(UNTITLED, summaryOf(listOf("" to "")))
        assertEquals(UNTITLED, summaryOf(emptyList()))
    }

    @Test
    fun aLabelNamesEachKindOfProblem() {
        val note = NoteId.generate()
        val revision = RevisionId.generate()

        fun waiting(reason: HiddenReason) = HiddenHead(revision, note, "t", "b", reason)
        val all =
            NoteAttention(
                note,
                "t",
                conflicts = listOf(OpenConflict(revision, note, revision, "t", "b")),
                hidden = listOf(waiting(HiddenReason.MISSING_ANCESTOR)),
                invalid =
                    listOf(
                        InvalidRevision(revision, note, InvalidReason.MALFORMED, "t", "b"),
                    ),
                drafts = listOf(ResolutionDraft(revision, note, true, revision, "t", "b")),
            )
        assertEquals(
            "$CONFLICT_LABEL, $WAITING_LABEL, $SET_ASIDE_LABEL, $UNSAVED_LABEL",
            all.label,
        )
        // A head that waits for a conflict that is gone, as the lists can lag a moment.
        val lagging =
            NoteAttention(
                note,
                "t",
                emptyList(),
                listOf(waiting(HiddenReason.OPEN_CONFLICT)),
                emptyList(),
                emptyList(),
            )
        assertEquals(WAITING_LABEL, lagging.label)
    }
}
