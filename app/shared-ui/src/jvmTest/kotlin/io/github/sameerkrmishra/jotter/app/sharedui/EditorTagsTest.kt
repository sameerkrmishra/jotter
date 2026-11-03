package io.github.sameerkrmishra.jotter.app.sharedui

import io.github.sameerkrmishra.jotter.core.model.TagName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EditorTagsTest : EditorTest() {
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
    fun aTagChangeKeepsASaveFailureWhileTheDraftIsDirty() =
        test {
            val editor = open(notes.create("", "base"))
            editor.body = "my text"
            database.whileLocked { assertFalse(editor.save()) }
            editor.tagInput = "work"

            editor.addTag()

            assertEquals(BUSY_MESSAGE, editor.message)
        }

    @Test
    fun aTagChangeClearsOldMessagesOtherwise() =
        test {
            val editor = open(notes.create("", "base"))
            editor.tagInput = "work"
            database.whileLocked { editor.addTag() }
            assertEquals(BUSY_MESSAGE, editor.message)

            editor.addTag()

            assertNull(editor.message)
        }

    @Test
    fun aValidTagReplacesTheInvalidTagMessageOfADirtyDraft() =
        test {
            val editor = open(notes.create("", "base"))
            editor.body = "my text"
            editor.addTag()
            assertEquals(INVALID_TAG_MESSAGE, editor.message)
            editor.tagInput = "work"

            editor.addTag()

            assertNull(editor.message)
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
}
