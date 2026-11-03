package io.github.sameerkrmishra.jotter.app.sharedui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EditorAutosaveTest : EditorTest() {
    @Test
    fun textRestoredDuringASaveIsNotOverwrittenByTheSavedNote() =
        test {
            val note = notes.create("", "base")
            val slowIo = StandardTestDispatcher(testScheduler)
            val editor = EditorViewModel(notes, scope, slowIo, MutableStateFlow(0), note)
            editor.body = "changed"
            val saving = scope.launch { editor.save() }

            editor.body = "base"
            saving.join()

            assertEquals("base", editor.body)
            assertTrue(editor.isDirty, "the store has the text that was saved")
            editor.close()
        }

    @Test
    fun theDraftSavesItselfOnceTypingStops() =
        test {
            val note = notes.create("", "base")
            val editor = open(note)

            editor.body = "first"
            testScheduler.advanceTimeBy(AUTOSAVE_IDLE_MS - 1)
            editor.body = "second"
            testScheduler.advanceTimeBy(AUTOSAVE_IDLE_MS - 1)
            assertEquals("base", notes.get(note.id)?.body, "typing goes on")

            testScheduler.advanceTimeBy(2)

            assertEquals("second", notes.get(note.id)?.body)
            assertFalse(editor.isDirty)
        }

    @Test
    fun unbrokenTypingSavesAtTheMaximumInterval() =
        test {
            val note = notes.create("", "base")
            val editor = open(note)
            val pause = AUTOSAVE_IDLE_MS - 1
            val keystrokes = AUTOSAVE_MAX_MS / pause

            repeat(keystrokes.toInt() - 1) {
                editor.body = "text $it"
                testScheduler.advanceTimeBy(pause)
            }
            assertEquals("base", notes.get(note.id)?.body, "not yet")

            repeat(2) {
                editor.body = "text at the end $it"
                testScheduler.advanceTimeBy(pause)
            }

            assertContains(notes.get(note.id)?.body.orEmpty(), "text at the end")
            assertEquals(2, notes.history(note.id).size, "one autosave in that minute")
        }

    @Test
    fun aSettledNoteIsNotSavedAgain() =
        test {
            val note = notes.create("", "base")
            val editor = open(note)
            editor.body = "text"
            settle()

            testScheduler.advanceTimeBy(10 * AUTOSAVE_MAX_MS)

            assertEquals(2, notes.history(note.id).size)
        }

    @Test
    fun aNewNoteIsCreatedOnceByTheAutosave() =
        test {
            val editor = open(null)
            editor.body = "first"
            settle()
            editor.body = "second"
            settle()

            assertEquals("second", notes.list().single().body)
        }

    @Test
    fun aFailedAutosaveKeepsTheDraftAndTheNextOneWorks() =
        test {
            val note = notes.create("", "base")
            val editor = open(note)
            database.failWrites()

            editor.body = "mine"
            settle()

            assertEquals("mine", editor.body)
            assertContains(editor.message.orEmpty(), "disk full")
            assertNull(app.pendingExit, "no dialog from the autosave")

            database.allowWrites()
            settle()
            assertEquals("mine", notes.get(note.id)?.body, "it tries again without more typing")
            assertNull(editor.message)
        }

    @Test
    fun theAutosaveWaitsForTheUsersDecisionOnAStaleDraft() =
        test {
            val note = notes.create("", "base")
            val editor = open(note)
            editFromOutside(note, "their text")
            editor.body = "mine"
            settle()
            assertEquals(EditorProblem.STALE, editor.problem)
            val revisions = notes.history(note.id).size

            editor.body = "mine and more"
            settle()

            assertEquals(EditorProblem.STALE, editor.problem)
            assertEquals(revisions, notes.history(note.id).size)
            assertEquals("mine and more", editor.body)
        }

    @Test
    fun textTypedDuringASlowAutosaveIsSavedByTheNextOne() =
        test {
            val note = notes.create("", "base")
            val io = HeldDispatcher()
            val editor = EditorViewModel(notes, scope, io, MutableStateFlow(0), note)

            editor.body = "first"
            testScheduler.advanceTimeBy(AUTOSAVE_IDLE_MS + 1)
            assertTrue(editor.isSaving)
            editor.body = "first and more"
            io.release()
            testScheduler.runCurrent()

            assertEquals("first", notes.get(note.id)?.body)
            assertEquals("first and more", editor.body)

            testScheduler.advanceTimeBy(AUTOSAVE_IDLE_MS + 1)
            io.release()
            testScheduler.runCurrent()

            assertEquals("first and more", notes.get(note.id)?.body)
            assertFalse(editor.isDirty)
            assertNull(editor.problem)
            editor.close()
        }
}
