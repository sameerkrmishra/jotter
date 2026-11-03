package io.github.sameerkrmishra.jotter.app.sharedui

import io.github.sameerkrmishra.jotter.core.storage.MAX_NOTE_CHARS
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest : EditorTest() {
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
    fun aDraftIsCleanAfterSavingTextThatStorageReplaces() =
        test {
            val editor = open(null)
            editor.body = "a\u0000b"

            assertTrue(editor.save())

            assertEquals("a\uFFFDb", editor.body)
            assertFalse(editor.isDirty)
        }

    @Test
    fun aDraftOverTheSizeLimitStaysHereAndSaysWhy() =
        test {
            val editor = open(null)
            editor.body = "x".repeat(MAX_NOTE_CHARS + 1)

            assertFalse(editor.save())

            assertEquals(TOO_LARGE_MESSAGE, editor.message)
            assertTrue(editor.isDirty)
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
    fun aCancelledSaveShowsNoFailure() =
        test {
            val note = notes.create("", "base")
            val slow =
                EditorViewModel(
                    notes,
                    scope,
                    StandardTestDispatcher(testScheduler),
                    MutableStateFlow(0),
                    note,
                )
            slow.body = "text"
            val saving = scope.launch { slow.save() }

            saving.cancel()
            settle()

            assertNull(slow.message)
            assertFalse(slow.isSaving)
            slow.close()
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
}
