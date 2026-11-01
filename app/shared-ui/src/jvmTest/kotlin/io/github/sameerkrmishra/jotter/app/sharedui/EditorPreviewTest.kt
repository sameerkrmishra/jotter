package io.github.sameerkrmishra.jotter.app.sharedui

import io.github.sameerkrmishra.jotter.app.sharedui.markdown.Paragraph
import io.github.sameerkrmishra.jotter.app.sharedui.markdown.Task
import io.github.sameerkrmishra.jotter.app.sharedui.markdown.parseMarkdown
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EditorPreviewTest : EditorTest() {
    @Test
    fun aTappedTaskChangesTheDraftOnly() =
        test {
            val note = notes.create("", "- [ ] a\n- [ ] b")
            val editor = open(note)
            val document = editor.parsed(editor.body)

            editor.toggleTask(document, document.rows[1].task!!)

            assertEquals("- [ ] a\n- [x] b", editor.body)
            assertTrue(editor.isDirty)
            assertEquals(note.body, notes.get(note.id)?.body)
        }

    @Test
    fun aTappedTaskIsAutosaved() =
        test {
            val note = notes.create("", "- [ ] a")
            val editor = open(note)
            val document = editor.parsed(editor.body)

            editor.toggleTask(document, document.rows.single().task!!)
            testScheduler.advanceTimeBy(AUTOSAVE_IDLE_MS + 1)

            assertEquals("- [x] a", notes.get(note.id)?.body)
            assertFalse(editor.isDirty)
        }

    @Test
    fun aTapOnTextThatChangedSinceDoesNothing() =
        test {
            val editor = open(notes.create("", "- [ ] a"))
            val document = editor.parsed(editor.body)
            editor.body = "x\n- [ ] a"

            editor.toggleTask(document, Task(offset = 3, isChecked = false))

            assertEquals("x\n- [ ] a", editor.body)
        }

    @Test
    fun aTapOnAStaleNoteLeavesItForTheUserToDecide() =
        test {
            val note = notes.create("", "- [ ] a")
            val editor = open(note)
            val document = editor.parsed(editor.body)
            editFromOutside(note, "theirs")

            editor.toggleTask(document, document.rows.single().task!!)
            editor.save()

            assertEquals(EditorProblem.STALE, editor.problem)
            assertEquals("- [x] a", editor.body)
            assertEquals("theirs", notes.get(note.id)?.body)
        }

    @Test
    fun parsingGoesThroughTheInjectedDispatcher() =
        test {
            val editor = open(notes.create("", "# a"))

            assertEquals(parseMarkdown("# a"), editor.parsed("# a"))
            assertIs<Paragraph>(editor.parsed("b").blocks.single())
        }
}
