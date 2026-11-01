package io.github.sameerkrmishra.jotter.app.sharedui

import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.TagName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest : ViewModelTest() {
    private fun Fixture.open(note: Note?): EditorViewModel {
        app.openEditor(note)
        settle()
        return assertNotNull(app.editor)
    }

    private fun Fixture.editFromOutside(
        note: Note,
        body: String,
    ) = database.fromOutside {
        it.notes.edit(note.id, note.revisionId, note.title, body)
    }

    private fun Fixture.deleteFromOutside(note: Note) =
        database.fromOutside {
            it.notes.setState(note.id, NoteState.TRASHED)
            it.notes.deleteForever(note.id)
        }

    @Test
    fun aNewNoteIsCreatedByItsFirstSave() =
        test {
            val editor = open(null)
            editor.body = "first"

            assertTrue(editor.save())
            editor.body = "second"
            assertTrue(editor.save())

            assertEquals("second", notes.list().single().body)
        }

    @Test
    fun aBlankNewNoteIsNotCreated() =
        test {
            val editor = open(null)
            editor.body = "  \n"

            app.leaveEditor()
            settle()

            assertNull(app.editor)
            assertEquals(emptyList(), notes.list())
        }

    @Test
    fun leavingTheEditorSavesTheDraft() =
        test {
            val note = notes.create("title", "body")
            val editor = open(note)
            editor.title = "new title"
            editor.body = "new body"
            assertTrue(editor.isDirty)

            app.leaveEditor()
            settle()

            assertNull(app.editor)
            assertEquals("new title", notes.get(note.id)?.title)
            assertEquals("new body", notes.get(note.id)?.body)
        }

    @Test
    fun aDraftIsCleanAfterSavingTextThatStorageReplaces() =
        test {
            val editor = open(null)
            editor.body = "a\u0000b"

            assertTrue(editor.save())

            assertEquals("a\uFFFDb", editor.body)
            assertFalse(editor.isDirty)
        }

    @Test
    fun textTypedDuringASaveStaysInTheDraft() =
        test {
            val note = notes.create("title", "body")
            val slowIo = StandardTestDispatcher(testScheduler)
            val editor =
                EditorViewModel(notes, scope, slowIo, MutableStateFlow(0), note)
            editor.title = "saved title"
            editor.body = "saved body"
            val saving = scope.launch { editor.save() }

            editor.title = "saved title and more"
            editor.body = "saved body and more"
            saving.join()

            assertEquals("saved body", notes.get(note.id)?.body)
            assertEquals("saved title and more", editor.title)
            assertEquals("saved body and more", editor.body)
            assertTrue(editor.isDirty)
            editor.close()
        }

    @Test
    fun aDraftCanBecomeANewNoteFromTheStart() =
        test {
            val editor = open(null)
            editor.body = "text"

            assertTrue(editor.saveAsNewNote())

            assertEquals(emptySet(), notes.list().single().tags)
        }

    @Test
    fun leavingWithNoEditorOpenDoesNothing() =
        test {
            app.leaveEditor()
            app.discardEditor()
            settle()

            assertNull(app.editor)
        }

    @Test
    fun aStaleSaveKeepsTheDraftAndReports() =
        test {
            val note = notes.create("", "base")
            val editor = open(note)
            editor.body = "my text"
            editFromOutside(note, "their text")

            assertFalse(editor.save())

            assertEquals("my text", editor.body)
            assertEquals(EditorProblem.STALE, editor.problem)
            assertEquals("their text", notes.get(note.id)?.body)
        }

    @Test
    fun leavingAfterAStaleSaveStaysInTheEditor() =
        test {
            val note = notes.create("", "base")
            val editor = open(note)
            editor.body = "my text"
            editFromOutside(note, "their text")

            app.leaveEditor()
            settle()

            assertEquals(editor, app.editor)
            assertEquals("my text", editor.body)
        }

    @Test
    fun aStaleDraftCanBecomeANewNoteWithTheSameTags() =
        test {
            val tags = setOf(TagName.parse("work"))
            val note = notes.create("", "base", tags)
            val editor = open(note)
            editor.body = "my text"
            editFromOutside(note, "their text")
            editor.save()

            assertTrue(editor.saveAsNewNote())

            assertNull(editor.problem)
            assertEquals(setOf("my text", "their text"), notes.list().map { it.body }.toSet())
            val copy = notes.list().single { it.id != note.id }
            assertEquals(tags, copy.tags)
            assertEquals(copy.id, editor.base?.id)
        }

    @Test
    fun aStaleDraftCanBeDroppedForTheStoredText() =
        test {
            val note = notes.create("", "base")
            val editor = open(note)
            editor.body = "my text"
            editFromOutside(note, "their text")
            editor.save()

            editor.reload()

            assertEquals("their text", editor.body)
            assertNull(editor.problem)
            assertFalse(editor.isDirty)
        }

    @Test
    fun reloadingANoteThatIsGoneReportsIt() =
        test {
            val note = notes.create("", "base")
            val editor = open(note)
            deleteFromOutside(note)

            editor.reload()

            assertEquals(EditorProblem.DELETED, editor.problem)
        }

    @Test
    fun reloadingANewNoteDoesNothing() =
        test {
            val editor = open(null)
            editor.body = "text"

            editor.reload()

            assertEquals("text", editor.body)
        }

    @Test
    fun savingANoteThatIsGoneKeepsTheDraft() =
        test {
            val note = notes.create("", "base")
            val editor = open(note)
            editor.body = "my text"
            deleteFromOutside(note)

            assertFalse(editor.save())

            assertEquals("my text", editor.body)
            assertEquals(EditorProblem.DELETED, editor.problem)

            assertTrue(editor.saveAsNewNote())
            assertEquals("my text", notes.list().single().body)
        }

    @Test
    fun aRefreshFindsANoteDeletedElsewhere() =
        test {
            val note = notes.create("", "base")
            val editor = open(note)
            deleteFromOutside(note)

            app.refresh()
            settle()

            assertEquals(EditorProblem.DELETED, editor.problem)
        }

    @Test
    fun aLockedDatabaseKeepsTheDraftAndReports() =
        test {
            val note = notes.create("", "base")
            val editor = open(note)
            editor.body = "my text"

            assertFalse(database.whileLocked { editor.save() })

            assertEquals("my text", editor.body)
            assertEquals(BUSY_MESSAGE, editor.message)

            assertTrue(editor.save())
            assertNull(editor.message)
            assertEquals("my text", notes.get(note.id)?.body)
        }

    @Test
    fun anUnchangedEditorFollowsAChangeFromElsewhere() =
        test {
            val note = notes.create("", "base")
            val editor = open(note)
            editFromOutside(note, "their text")

            app.refresh()
            settle()

            assertEquals("their text", editor.body)
            editor.body = "mine on top"
            assertTrue(editor.save(), "the next edit builds on the text it now shows")
        }

    @Test
    fun aDraftIsNotReplacedByAChangeFromElsewhere() =
        test {
            val note = notes.create("", "base")
            val editor = open(note)
            editor.body = "my text"
            val theirs = editFromOutside(note, "their text")

            app.refresh()
            settle()

            assertEquals("my text", editor.body)
            assertNotEquals(theirs.revisionId, editor.base?.revisionId)
        }

    @Test
    fun aPinOrTagChangeDuringAnEditDoesNotMakeItStale() =
        test {
            val note = notes.create("", "base")
            val editor = open(note)
            editor.body = "my text"
            notes.setPinned(note.id, true)
            settle()

            assertTrue(editor.save())

            assertTrue(notes.get(note.id)!!.isPinned)
        }

    @Test
    fun aTagIsStoredAtOnceAndKeepsTheDraft() =
        test {
            val note = notes.create("", "base")
            val editor = open(note)
            editor.body = "my text"
            editor.tagInput = "  Shopping   list "

            editor.addTag()

            assertEquals(setOf(TagName.parse("Shopping list")), notes.get(note.id)?.tags)
            assertEquals(notes.get(note.id)?.tags, editor.tags)
            assertEquals("", editor.tagInput)
            assertEquals("my text", editor.body)
            assertEquals("base", notes.get(note.id)?.body, "the text is saved separately")
            assertTrue(editor.save(), "a tag change does not make the draft stale")
        }

    @Test
    fun aTagCanBeRemoved() =
        test {
            val work = TagName.parse("work")
            val note = notes.create("", "base", setOf(work))
            val editor = open(note)

            editor.removeTag(work)

            assertEquals(emptySet(), notes.get(note.id)?.tags)
            assertEquals(emptySet(), editor.tags)
        }

    @Test
    fun anInvalidTagShowsAMessageAndStaysInTheField() =
        test {
            val note = notes.create("", "base")
            val editor = open(note)
            listOf("", "   ", "a\u0001b", "x".repeat(TagName.MAX_LENGTH + 1)).forEach { input ->
                editor.tagInput = input

                editor.addTag()

                assertEquals(INVALID_TAG_MESSAGE, editor.message)
                assertEquals(input, editor.tagInput)
            }
            assertEquals(emptySet(), notes.get(note.id)?.tags)

            editor.tagInput = "fine"
            editor.addTag()
            assertNull(editor.message)
        }

    @Test
    fun aNewNoteKeepsItsTagsUntilItsFirstSave() =
        test {
            val editor = open(null)
            editor.tagInput = "work"
            editor.addTag()
            editor.tagInput = "home"
            editor.addTag()
            editor.removeTag(TagName.parse("home"))
            assertEquals(emptyList(), notes.list())

            editor.body = "text"
            assertTrue(editor.save())

            assertEquals(setOf(TagName.parse("work")), notes.list().single().tags)
        }

    @Test
    fun aTagAloneDoesNotCreateANote() =
        test {
            val editor = open(null)
            editor.tagInput = "work"
            editor.addTag()
            editor.body = " "
            assertFalse(editor.isDirty)
            settle()

            app.leaveEditor()
            settle()

            assertNull(app.editor)
            assertEquals(emptyList(), notes.list())
        }

    @Test
    fun aTagChangeLeavesAStaleProblemInPlace() =
        test {
            val note = notes.create("", "base")
            val editor = open(note)
            editFromOutside(note, "their text")
            editor.body = "mine"
            editor.save()
            editor.tagInput = "work"

            editor.addTag()

            assertEquals(EditorProblem.STALE, editor.problem)
            assertEquals("mine", editor.body)
            assertFalse(editor.save(), "their text is still protected")
        }

    @Test
    fun aFailedTagChangeKeepsTheInput() =
        test {
            val note = notes.create("", "base")
            val editor = open(note)
            database.failWrites()
            editor.tagInput = "work"

            editor.addTag()

            assertEquals("work", editor.tagInput)
            assertContains(editor.message.orEmpty(), "disk full")
        }

    @Test
    fun anOpenEditorShowsATagAddedElsewhere() =
        test {
            val note = notes.create("", "base")
            val editor = open(note)
            editor.body = "my text"
            settle()
            editor.body = "more text"
            database.fromOutside { it.notes.addTag(note.id, TagName.parse("work")) }

            app.refresh()
            testScheduler.runCurrent()

            assertEquals(setOf(TagName.parse("work")), editor.tags)
            assertEquals("more text", editor.body)
        }

    @Test
    fun aFailedReadLeavesTheDraftAlone() =
        test {
            val editor = open(notes.create("", "base"))
            store.close()

            app.refresh()
            settle()

            assertEquals(READ_FAILED_MESSAGE, editor.message)
            assertEquals("base", editor.body)
        }

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
    fun anEditorStopsObservingWhenClosed() =
        test {
            val note = notes.create("", "base")
            val editor = open(note)
            app.discardEditor()
            editFromOutside(note, "their text")

            app.refresh()
            settle()

            assertEquals("base", editor.body)
            assertNull(app.editor)
        }

    @Test
    fun aDraftIsSavedBeforeExit() =
        test {
            var exits = 0
            app.requestExit { exits++ }
            settle()
            assertEquals(1, exits, "nothing is open")

            val note = notes.create("", "base")
            open(note).body = "my text"
            app.requestExit { exits++ }
            settle()

            assertEquals(2, exits)
            assertEquals("my text", notes.get(note.id)?.body)
        }

    @Test
    fun aStaleDraftHoldsUpExitUntilTheUserDecides() =
        test {
            val note = notes.create("", "base")
            val editor = open(note)
            editor.body = "my text"
            editFromOutside(note, "their text")
            var exits = 0
            app.requestExit { exits++ }
            settle()
            assertEquals(0, exits)
            assertTrue(app.pendingExit != null)

            app.keepEditing()

            assertNull(app.pendingExit)
            assertEquals("my text", editor.body)
            assertEquals(0, exits)

            app.requestExit { exits++ }
            settle()
            app.exitWithoutSaving()

            assertEquals(1, exits)
            assertNull(app.pendingExit)
        }

    @Test
    fun aDiskErrorKeepsTheDraftAndShowsTheReason() =
        test {
            val note = notes.create("", "base")
            val editor = open(note)
            editor.body = "my text"
            database.failWrites()

            assertFalse(editor.save())

            assertEquals("my text", editor.body)
            assertContains(editor.message.orEmpty(), SAVE_FAILED_MESSAGE)
            assertContains(editor.message.orEmpty(), "disk full")

            database.allowWrites()
            assertTrue(editor.save())
            assertNull(editor.message)
        }

    @Test
    fun aDiskErrorOnBackStaysInTheEditor() =
        test {
            val editor = open(notes.create("", "base"))
            editor.body = "my text"
            database.failWrites()

            app.leaveEditor()
            settle()

            assertEquals(editor, app.editor)
            assertContains(editor.message.orEmpty(), "disk full")
        }

    @Test
    fun aDiskErrorOnExitAsksTheUser() =
        test {
            open(notes.create("", "base")).body = "my text"
            database.failWrites()
            var exited = false

            app.requestExit { exited = true }
            settle()

            assertFalse(exited)
            assertTrue(app.pendingExit != null)
        }

    @Test
    fun aFailedReloadKeepsTheDraft() =
        test {
            val editor = open(notes.create("", "base"))
            editor.body = "my text"
            store.close()

            editor.reload()

            assertEquals("my text", editor.body)
            assertContains(editor.message.orEmpty(), SAVE_FAILED_MESSAGE)
        }

    @Test
    fun loadingTheStoredNoteClearsAnOldMessage() =
        test {
            val note = notes.create("", "base")
            val editor = open(note)
            editor.body = "my text"
            database.whileLocked { assertFalse(editor.save()) }
            assertEquals(BUSY_MESSAGE, editor.message)

            editor.reload()

            assertNull(editor.message)
        }

    private fun Fixture.slowApp() =
        AppViewModel(notes, scope, StandardTestDispatcher(testScheduler))

    @Test
    fun textTypedDuringTheSaveOnBackIsSavedToo() =
        test {
            val note = notes.create("", "base")
            val slow = slowApp()
            slow.openEditor(note)
            val editor = assertNotNull(slow.editor)
            editor.body = "first"

            slow.leaveEditor()
            assertTrue(editor.isSaving, "the screen can show that it is saving")
            editor.body = "first and more"
            settle()

            assertFalse(editor.isSaving)
            assertNull(slow.editor)
            assertEquals("first and more", notes.get(note.id)?.body)
        }

    @Test
    fun textTypedDuringTheSaveOnCloseIsSavedToo() =
        test {
            val note = notes.create("", "base")
            val slow = slowApp()
            slow.openEditor(note)
            val editor = assertNotNull(slow.editor)
            editor.body = "first"
            var exited = false

            slow.requestExit { exited = true }
            editor.body = "first and more"
            settle()

            assertTrue(exited)
            assertEquals("first and more", notes.get(note.id)?.body)
        }
}
