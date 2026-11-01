package io.github.sameerkrmishra.jotter.app.sharedui.markdown

import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskToggleTest {
    private fun tasks(text: String) = parseMarkdown(text).rows.mapNotNull { it.task }

    /** The text after ticking or clearing its only task, or the one at [index]. */
    private fun toggle(
        text: String,
        index: Int = 0,
    ) = toggled(text, tasks(text)[index])

    @Test
    fun tickingAndClearingChangeTheBoxOnly() {
        assertEquals("- [x] milk", toggle("- [ ] milk"))
        assertEquals("- [ ] milk", toggle("- [x] milk"))
        assertEquals("- [ ] milk", toggle("- [X] milk"))
    }

    @Test
    fun duplicateItemsAreToldApartByPosition() {
        val text = "- [ ] milk\n- [ ] milk\n- [ ] milk"

        assertEquals("- [ ] milk\n- [x] milk\n- [ ] milk", toggle(text, 1))
    }

    @Test
    fun nestedItemsAreTheirOwn() {
        val text = "- [ ] a\n  - [ ] a\n    - [ ] a\n- [ ] b"

        assertEquals("- [ ] a\n  - [ ] a\n    - [x] a\n- [ ] b", toggle(text, 2))
    }

    @Test
    fun crlfKeepsItsLineEndsAndTheRightOffset() {
        val text = "first\r\n\r\n- [ ] a\r\n- [ ] b\r\n"

        assertEquals("first\r\n\r\n- [ ] a\r\n- [x] b\r\n", toggle(text, 1))
    }

    @Test
    fun aLoneCarriageReturnEndsALineToo() {
        assertEquals("- [ ] a\r- [x] b", toggle("- [ ] a\r- [ ] b", 1))
    }

    @Test
    fun everyBulletAndOrderedItemsCount() {
        assertEquals("* [x] a", toggle("* [ ] a"))
        assertEquals("+ [x] a", toggle("+ [ ] a"))
        assertEquals("1. [x] a", toggle("1. [ ] a"))
        assertEquals("12) [x] a", toggle("12) [ ] a"))
    }

    @Test
    fun aTaskInAQuoteCounts() {
        assertEquals("> - [x] a", toggle("> - [ ] a"))
    }

    @Test
    fun anItemWithNoTextIsATaskAsTheParserSays() {
        assertEquals("- [x]", toggle("- [ ]"))
    }

    @Test
    fun whatOnlyResemblesACheckboxIsNotOne() {
        val notTasks =
            listOf(
                "```\n- [ ] in a fence\n```",
                "~~~\n- [x] in a fence\n~~~",
                "    - [ ] indented code",
                "> ```\n> - [ ] quoted fence\n> ```",
                "- ```\n  - [ ] fence in an item\n  ```",
                "text with [ ] in it",
                "[ ] not in a list",
                "- not first [ ] in the item",
                "- `[ ]` in code",
                "- [y] another letter",
                "- [ ]not spaced",
                "<div>\n- [ ] raw html\n</div>",
            )
        for (text in notTasks) {
            assertEquals(emptyList(), tasks(text), text)
        }
    }

    @Test
    fun aTaskAfterACodeBlockIsFoundAfterItsLookalike() {
        val text = "```\n- [ ] code\n```\n\n- [ ] real"

        assertEquals("```\n- [ ] code\n```\n\n- [x] real", toggle(text))
    }

    @Test
    fun aNestedListOnTheMarkersLineCountsItsOwnBox() {
        assertEquals(listOf(Task(5, false)), tasks("- - [ ] a"))
    }

    @Test
    fun theBoxIsGoneFromTheTextOfTheItem() {
        val row = parseMarkdown("- [ ] [x] a").rows.single()

        assertEquals("[x] a", (row.block as Paragraph).spans.joinToString("") { it.text })
        assertNull(parseMarkdown("- a").rows.single().task)
    }

    @Test
    fun togglingTwiceRestoresTheTextAndChangesOneBoxOnly() =
        runTest {
            checkAll(Arb.list(line, 1..12), Arb.boolean()) { lines, isCrlf ->
                val text = lines.joinToString(if (isCrlf) "\r\n" else "\n")
                val found = tasks(text)

                found.forEachIndexed { index, task ->
                    val once = toggled(text, task)
                    val changed = text.indices.filter { text[it] != once[it] }

                    assertEquals(text.length, once.length)
                    assertEquals(listOf(task.offset), changed, text)
                    assertEquals("[", text.substring(task.offset - 1, task.offset))
                    assertEquals("]", text.substring(task.offset + 1, task.offset + 2))
                    assertEquals(text, toggled(once, tasks(once)[index]), text)
                    val expected =
                        found.mapIndexed { other, box ->
                            if (other == index) box.copy(isChecked = !box.isChecked) else box
                        }
                    assertEquals(expected, tasks(once), text)
                }
            }
        }

    @Test
    fun theGeneratedNotesHaveTasks() =
        runTest {
            var withTasks = 0
            checkAll(Arb.list(line, 1..12)) { lines ->
                if (tasks(lines.joinToString("\n")).isNotEmpty()) withTasks++
            }
            assertTrue(withTasks > 0)
        }

    private companion object {
        val line =
            Arb.element(
                "- [ ] milk",
                "- [x] milk",
                "* [ ] milk",
                "+ [x] eggs",
                "1. [ ] one",
                "2) [x] two",
                "  - [ ] nested",
                "    - [x] deeper",
                "> - [ ] quoted",
                "- plain item",
                "not a [ ] box",
                "```",
                "    - [ ] maybe code",
                "",
                "# heading",
                "<!-- - [ ] -->",
            )
    }
}
