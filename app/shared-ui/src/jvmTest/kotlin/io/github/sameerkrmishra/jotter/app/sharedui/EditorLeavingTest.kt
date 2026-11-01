package io.github.sameerkrmishra.jotter.app.sharedui

import io.github.sameerkrmishra.jotter.core.model.NoteState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EditorLeavingTest : EditorTest() {
    private fun Fixture.slowApp() =
        AppViewModel(notes, scope, StandardTestDispatcher(testScheduler))

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
    fun leavingWithNoEditorOpenDoesNothing() =
        test {
            app.leaveEditor()
            app.discardEditor()
            settle()

            assertNull(app.editor)
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
    fun aNoteCreatedHereAndEmptiedGoesToTheTrashOnBack() =
        test {
            val editor = open(null)
            editor.body = "text"
            settle()
            val id = notes.list().single().id
            editor.body = ""

            app.leaveEditor()
            settle()

            assertNull(app.editor)
            assertEquals(NoteState.TRASHED, notes.get(id)?.state)
        }

    @Test
    fun anOlderNoteThatIsEmptiedStays() =
        test {
            val note = notes.create("", "base")
            open(note).body = ""

            app.leaveEditor()
            settle()

            assertEquals(NoteState.ACTIVE, notes.get(note.id)?.state)
        }

    @Test
    fun aNewNoteWithTextStays() =
        test {
            open(null).body = "text"

            app.leaveEditor()
            settle()

            assertEquals(NoteState.ACTIVE, notes.list().single().state)
        }

    @Test
    fun aNoteGivenTextElsewhereIsNotTrashed() =
        test {
            val editor = open(null)
            editor.body = "text"
            settle()
            val note = notes.list().single()
            editor.body = ""
            assertTrue(editor.save())
            database.fromOutside {
                it.notes.edit(note.id, assertNotNull(editor.base).revisionId, "", "theirs")
            }

            app.leaveEditor()
            settle()

            assertEquals(NoteState.ACTIVE, notes.get(note.id)?.state)
        }

    @Test
    fun aNoteDeletedElsewhereHasNothingToTrash() =
        test {
            val editor = open(null)
            editor.body = "text"
            settle()
            val note = notes.list().single()
            editor.body = ""
            assertTrue(editor.save())
            deleteFromOutside(note)

            assertTrue(editor.finish())
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
