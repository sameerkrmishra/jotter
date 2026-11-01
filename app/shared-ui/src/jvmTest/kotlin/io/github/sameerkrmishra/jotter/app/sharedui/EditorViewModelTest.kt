package io.github.sameerkrmishra.jotter.app.sharedui

import io.github.sameerkrmishra.jotter.core.model.Note
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.TagName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @OptIn(ExperimentalCoroutinesApi::class)
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
    fun aFailedReadLeavesTheDraftAlone() =
        test {
            val editor = open(notes.create("", "base"))
            editor.body = "my text"
            store.close()

            app.refresh()
            settle()

            assertEquals(READ_FAILED_MESSAGE, editor.message)
            assertEquals("my text", editor.body)
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
            assertTrue(app.saveBeforeExit(), "nothing is open")

            val note = notes.create("", "base")
            open(note).body = "my text"

            assertTrue(app.saveBeforeExit())
            assertEquals("my text", notes.get(note.id)?.body)
        }

    @Test
    fun aStaleDraftHoldsUpExit() =
        test {
            val note = notes.create("", "base")
            open(note).body = "my text"
            editFromOutside(note, "their text")

            assertFalse(app.saveBeforeExit())
        }
}
