@file:OptIn(ExperimentalTestApi::class)

package io.github.sameerkrmishra.jotter.app.sharedui.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

private const val MEGABYTE = 1 shl 20

/** What the preview shows and does, on the real renderer. */
class MarkdownPreviewTest {
    private fun ComposeUiTest.show(
        text: String,
        onToggle: (Task) -> Unit = {},
    ) {
        setContent {
            MaterialTheme { MarkdownPreview(parseMarkdown(text), onToggle) }
        }
    }

    private fun SemanticsNodeInteraction.annotated() =
        fetchSemanticsNode().config[SemanticsProperties.Text].single()

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
        return layouts.single()
    }

    private fun ComposeUiTest.fontSize(text: String) =
        onNodeWithText(text)
            .layout()
            .layoutInput.style.fontSize.value

    private fun ComposeUiTest.left(text: String) = onNodeWithText(text).getBoundsInRoot().left

    private fun ComposeUiTest.top(text: String) = onNodeWithText(text).getBoundsInRoot().top

    @Test
    fun headingsGetSmallerWithTheirLevel() =
        runComposeUiTest {
            show("# one\n\n## two\n\n### three\n\n#### four\n\n##### five\n\n###### six\n\nbody")

            val sizes = listOf("one", "two", "three", "four", "five").map { fontSize(it) }

            assertEquals(sizes.sortedDescending(), sizes)
            assertEquals(sizes.size, sizes.toSet().size)
            assertEquals(fontSize("five"), fontSize("six"))
        }

    @Test
    fun inlineStylesShow() =
        runComposeUiTest {
            show("**b** *i* ~~s~~ `c`")

            val text = onNodeWithText("b i s c").annotated()

            fun styleOf(part: String) =
                text.spanStyles.single { text.substring(it.start, it.end) == part }.item

            assertEquals(FontWeight.Bold, styleOf("b").fontWeight)
            assertEquals(FontStyle.Italic, styleOf("i").fontStyle)
            assertEquals(TextDecoration.LineThrough, styleOf("s").textDecoration)
            assertEquals(FontFamily.Monospace, styleOf("c").fontFamily)
        }

    @Test
    fun aLinkShowsWhereItGoesAndATapOpensNothing() =
        runComposeUiTest {
            val opened = mutableListOf<String>()
            val handler =
                object : UriHandler {
                    override fun openUri(uri: String) {
                        opened += uri
                    }
                }
            setContent {
                CompositionLocalProvider(LocalUriHandler provides handler) {
                    MaterialTheme {
                        MarkdownPreview(parseMarkdown("[site](https://example.org)"), {})
                    }
                }
            }

            val link = onNodeWithText("site (https://example.org)")
            link.assertIsDisplayed()
            val style =
                link
                    .annotated()
                    .spanStyles
                    .single()
                    .item
            assertEquals(TextDecoration.Underline, style.textDecoration)

            link.performClick()

            assertEquals(emptyList(), opened)
        }

    @Test
    fun codeBlocksKeepTheirLinesInAFixedFont() =
        runComposeUiTest {
            show("```\na\n  b\n```")

            val code = onNodeWithText("a\n  b").layout().layoutInput.style

            assertEquals(FontFamily.Monospace, code.fontFamily)
        }

    @Test
    fun rawHtmlAndImagesShowAsTheirText() =
        runComposeUiTest {
            show("<b>x</b> ![a](https://example.org/a.png)")

            onNodeWithText("<b>x</b> ![a](https://example.org/a.png)").assertIsDisplayed()
        }

    @Test
    fun aRuleTakesItsOwnPlaceBetweenBlocks() =
        runComposeUiTest {
            show("above\n\n---\n\nbelow")

            val gap = top("below") - onNodeWithText("above").getBoundsInRoot().bottom

            assertTrue(gap.value > 2 * ROW_GAP, "the rule's row is between them: $gap")
        }

    @Test
    fun listsAndQuotesIndentByLevel() =
        runComposeUiTest {
            show("top\n\n- one\n  - two\n\n> quoted\n\n1. first\n2. second")

            assertTrue(left("two") > left("one"))
            assertTrue(left("one") > left("top"))
            assertTrue(left("quoted") > left("top"))
            assertEquals(2, onAllNodesWithText("•").fetchSemanticsNodes().size)
            onNodeWithText("2.").assertIsDisplayed()
        }

    @Test
    fun aListInsideAnItemStartsBelowTheOuterMarker() =
        runComposeUiTest {
            show("- - inner")

            val markers = onAllNodesWithText("•")
            markers.assertCountEquals(2)
            assertTrue(markers[1].getBoundsInRoot().top > markers[0].getBoundsInRoot().top)
            assertTrue(left("inner") > markers[1].getBoundsInRoot().left)
        }

    @Test
    fun checkboxesShowTheirStateAndReportTheirTask() =
        runComposeUiTest {
            val tapped = mutableListOf<Task>()
            show("- [ ] open\n- [x] done") { tapped += it }

            val open = onNodeWithTag(taskTag(3))
            val done = onNodeWithTag(taskTag(14))
            open.assertIsOff()
            done.assertIsOn()

            done.performClick()
            open.performClick()

            assertEquals(listOf(Task(14, true), Task(3, false)), tapped)
        }

    @Test
    fun aHundredThousandTasksShowOnlyWhatFitsAndScroll() =
        runComposeUiTest {
            val lines = MEGABYTE / 11
            show("- [ ] task\n".repeat(lines - 1) + "- [x] last\n")

            onAllNodesWithText("last").assertCountEquals(0)
            assertTrue(onAllNodesWithText("task").fetchSemanticsNodes().size < lines / 1_000)
            onNodeWithTag(MARKDOWN_PREVIEW).performScrollToIndex(lines - 1)
            onNodeWithText("last").assertIsDisplayed()
        }

    @Test
    fun aMegabyteParagraphRenders() =
        runComposeUiTest {
            val document = parseMarkdown("word ".repeat(MEGABYTE / 5) + "\n\nthe end")
            setContent { MaterialTheme { MarkdownPreview(document, {}) } }

            val elapsed =
                measureTime {
                    onNodeWithTag(MARKDOWN_PREVIEW).performScrollToIndex(document.rows.lastIndex)
                    onNodeWithText("the end").assertIsDisplayed()
                }

            assertTrue(elapsed.inWholeSeconds < 5, "took $elapsed")
        }

    private companion object {
        const val ROW_GAP = 4
    }
}
