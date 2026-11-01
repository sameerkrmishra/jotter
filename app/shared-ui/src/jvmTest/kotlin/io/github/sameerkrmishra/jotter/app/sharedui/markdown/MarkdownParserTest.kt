package io.github.sameerkrmishra.jotter.app.sharedui.markdown

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.measureTime

private const val MEGABYTE = 1 shl 20

class MarkdownParserTest {
    private fun blocks(text: String) = parseMarkdown(text).blocks

    private fun single(text: String) = blocks(text).single()

    private fun spans(text: String) = assertIs<Paragraph>(single(text)).spans

    private fun plainText(text: String) = spans(text).joinToString("") { it.text }

    @Test
    fun nothingIsNoBlocks() {
        assertEquals(emptyList(), blocks(""))
        assertEquals(emptyList(), blocks("\n  \n"))
    }

    @Test
    fun headingsHaveTheirLevelAndTheirInlines() {
        assertEquals(
            Heading(2, listOf(Span("Plan "), Span("now", SpanLook(isItalic = true)))),
            single("## Plan *now*"),
        )
        assertEquals(Heading(6, listOf(Span("Deep"))), single("###### Deep"))
        assertEquals(Heading(1, listOf(Span("Title"))), single("Title\n==="))
        assertEquals(Heading(2, listOf(Span("Sub"))), single("Sub\n---"))
    }

    @Test
    fun paragraphsAreSplitByBlankLines() {
        assertEquals(
            listOf(Paragraph(listOf(Span("one"))), Paragraph(listOf(Span("two")))),
            blocks("one\n\ntwo"),
        )
    }

    @Test
    fun aLineBreakStaysAndTheIndentOfTheNextLineGoes() {
        assertEquals("one\ntwo", plainText("one\n   two"))
        assertEquals("one\ntwo", plainText("one\\\ntwo"))
    }

    @Test
    fun inlineStylesNest() {
        assertEquals(
            listOf(
                Span("a "),
                Span("bold ", SpanLook(isBold = true)),
                Span("both", SpanLook(isBold = true, isItalic = true)),
                Span(" "),
                Span("gone", SpanLook(isStruck = true)),
            ),
            spans("a **bold *both*** ~~gone~~"),
        )
    }

    @Test
    fun inlineCodeIsLiteralAndLosesOnePaddingSpace() {
        val code = SpanLook(isCode = true)
        assertEquals(listOf(Span("a *b* &amp;", code)), spans("`a *b* &amp;`"))
        assertEquals(listOf(Span(" a ", code)), spans("`  a  `"))
        assertEquals(listOf(Span("a b", code)), spans("`a\nb`"))
        assertEquals(listOf(Span("  ", code)), spans("`  `"))
        assertEquals(listOf(Span(" a", code)), spans("` a`"))
        assertEquals(listOf(Span("a ", code)), spans("`a `"))
    }

    @Test
    fun escapesAndEntitiesAreDecodedOutsideCode() {
        assertEquals("*not bold* & more", plainText("\\*not bold\\* &amp; more"))
        assertEquals("1 < 2 > 0 \" &", plainText("1 < 2 > 0 \" &"))
        assertEquals("é &nosuch;", plainText("&eacute; &nosuch;"))
    }

    @Test
    fun entitiesBeyondOneCharKeepTheirWholeCharacter() {
        assertEquals("😀 😀 😀", plainText("&#128512; &#x1F600; &#X1f600;"))
        assertEquals("𝔄", plainText("&Afr;"))
        assertEquals("�", plainText("&#xFFFFFFFF;"))
        assertEquals("&#128512;", plainText("\\&#128512;"))
        assertEquals("é", plainText("&#233;"))
    }

    @Test
    fun aLinkKeepsItsUrlAndShowsIt() {
        assertEquals(
            listOf(
                Span(
                    "see (https://example.org/a_b.c)",
                    SpanLook(url = "https://example.org/a_b.c"),
                ),
            ),
            spans("[see](https://example.org/a_b.c \"a title\")"),
        )
    }

    @Test
    fun aLinkCanHaveStylesInsideAndNoDestination() {
        val link = SpanLook(url = "u")
        assertEquals(
            listOf(Span("a", link), Span("b", link.copy(isBold = true)), Span(" (u)", link)),
            spans("[a**b**](<u>)"),
        )
        assertEquals(listOf(Span("a", SpanLook(url = ""))), spans("[a]()"))
    }

    @Test
    fun referenceLinksUseTheirDefinitionWhichDoesNotShow() {
        val link = SpanLook(url = "https://example.org")
        assertEquals(
            listOf(Span("text (https://example.org)", link)),
            spans("[text][ref]\n\n[ref]: https://example.org"),
        )
        assertEquals(
            listOf(Span("ref (https://example.org)", link)),
            spans("[ref]\n\n[ref]: <https://example.org>"),
        )
    }

    @Test
    fun aReferenceWithNoDefinitionIsTextWithItsStyles() {
        assertEquals(
            listOf(Span("["), Span("a", SpanLook(isBold = true)), Span("]")),
            spans("[**a**]"),
        )
    }

    @Test
    fun autolinksAreLinks() {
        val url = "https://example.org/x"
        assertEquals(listOf(Span(url, SpanLook(url = url))), spans(url))
        assertEquals(listOf(Span(url, SpanLook(url = url))), spans("<$url>"))
    }

    @Test
    fun aUrlAsALinksTextKeepsTheLinksTarget() {
        assertEquals(
            listOf(
                Span(
                    "https://example.org/a (https://example.org/b)",
                    SpanLook(url = "https://example.org/b"),
                ),
            ),
            spans("[https://example.org/a](https://example.org/b)"),
        )
    }

    @Test
    fun rawHtmlAndImagesAreTheirOwnPlainText() {
        assertEquals("<b>x</b>", plainText("<b>x</b>"))
        assertEquals(
            "![a cat](https://example.org/cat.png)",
            plainText("![a cat](https://example.org/cat.png)"),
        )
        assertEquals(
            "<div>\n<img src=\"https://example.org/cat.png\">\n</div>",
            plainText("<div>\n<img src=\"https://example.org/cat.png\">\n</div>"),
        )
    }

    @Test
    fun fencedCodeIsLiteral() {
        assertEquals(CodeBlock("a\n\n  *b*"), single("```kotlin\na\n\n  *b*\n```"))
        assertEquals(CodeBlock(""), single("```\n```"))
        assertEquals(CodeBlock("not closed\nyet"), single("```\nnot closed\nyet"))
        assertEquals(CodeBlock(""), single("```"))
    }

    @Test
    fun fencedCodeInAQuoteLosesTheQuoteMarkers() {
        assertEquals(Quote(listOf(CodeBlock("a\nb"))), single("> ```\n> a\n> b\n> ```"))
    }

    @Test
    fun indentedCodeLosesItsIndentOnly() {
        assertEquals(CodeBlock("a\n  b"), single("    a\n      b"))
        assertEquals(CodeBlock("a\n\tb"), single("\ta\n\t\tb"))
    }

    @Test
    fun aTableStaysAsItWasTyped() {
        assertEquals(
            CodeBlock("| a | b |\n|---|---|\n| 1 | 2 |"),
            single("| a | b |\n|---|---|\n| 1 | 2 |"),
        )
    }

    @Test
    fun rules() {
        assertEquals(listOf(Paragraph(listOf(Span("a"))), Rule), blocks("a\n\n---"))
        assertEquals(Rule, single("***"))
    }

    @Test
    fun quotesNest() {
        assertEquals(
            Quote(
                listOf(
                    Paragraph(listOf(Span("a\nb"))),
                    Quote(listOf(Paragraph(listOf(Span("c"))))),
                ),
            ),
            single("> a\n> b\n>\n> > c"),
        )
    }

    @Test
    fun anAlertIsAQuoteWithItsTitle() {
        assertEquals(
            Quote(listOf(Paragraph(listOf(Span("[!NOTE]"))), Paragraph(listOf(Span("a"))))),
            single("> [!NOTE]\n> a"),
        )
    }

    @Test
    fun bulletListsNest() {
        val list = assertIs<ListBlock>(single("- a\n  - b\n- c"))
        assertEquals(listOf("•", "•"), list.items.map { it.marker })
        assertEquals(
            ListBlock(listOf(ListItem("•", null, listOf(Paragraph(listOf(Span("b"))))))),
            list.items[0].blocks[1],
        )
    }

    @Test
    fun orderedListsCountFromTheirFirstNumberWithItsDelimiter() {
        val list = assertIs<ListBlock>(single("3. a\n1. b\n1. c"))
        assertEquals(listOf("3.", "4.", "5."), list.items.map { it.marker })
        val parens = assertIs<ListBlock>(single("1) a\n2) b"))
        assertEquals(listOf("1)", "2)"), parens.items.map { it.marker })
    }

    @Test
    fun anEmptyItemHasNoBlocks() {
        assertEquals(ListBlock(listOf(ListItem("•", null, emptyList()))), single("-"))
    }

    @Test
    fun rowsCarryNestingInReadingOrder() {
        val rows = parseMarkdown("> - a\n>   - [x] b\n>\n>   c\n\n- - d\n-").rows
        assertEquals(
            listOf(
                Row(Paragraph(listOf(Span("a"))), 1, listOf(0), "•"),
                Row(Paragraph(listOf(Span("b"))), 2, listOf(0), "•", Task(13, true)),
                Row(Paragraph(listOf(Span("c"))), 2, listOf(0)),
                Row(Paragraph(emptyList()), 0, emptyList(), "•"),
                Row(Paragraph(listOf(Span("d"))), 1, emptyList(), "•"),
                Row(Paragraph(emptyList()), 0, emptyList(), "•"),
            ),
            rows,
        )
    }

    private fun Row.text() =
        when (val shown = block) {
            is Heading -> shown.spans.joinToString("") { it.text }
            is Paragraph -> shown.spans.joinToString("") { it.text }
            is CodeBlock -> shown.text
            Rule -> ""
        }

    private fun assertSplit(
        rows: List<Row>,
        expected: String,
    ) {
        assertTrue(rows.size > 1)
        assertTrue(rows.all { it.text().length <= MAX_ROW_TEXT })
        assertEquals(expected, rows.joinToString("") { it.text() })
    }

    @Test
    fun longTextIsSplitOverRowsAtSpacesAndLosesNothing() {
        val text = "word ".repeat(MAX_ROW_TEXT).trim()
        val rows = parseMarkdown(text).rows

        assertSplit(rows, text)
        assertTrue(rows.dropLast(1).all { it.text().endsWith(" ") })
    }

    @Test
    fun longTextKeepsItsLooks() {
        val bold = "b".repeat(MAX_ROW_TEXT - 1)
        val rows = parseMarkdown("**$bold** plain").rows

        assertEquals(
            listOf(listOf(Span(bold, SpanLook(isBold = true))), listOf(Span(" plain"))),
            rows.map { (it.block as Paragraph).spans },
        )
    }

    @Test
    fun aLongWordIsSplitButNeverInsideACharacter() {
        val word = "x" + "😀".repeat(MAX_ROW_TEXT)
        val rows = parseMarkdown(word).rows

        assertSplit(rows, word)
        assertTrue(rows.none { it.text().last().isHighSurrogate() })
    }

    @Test
    fun longHeadingsAndCodeAreSplitToo() {
        val text = "a ".repeat(MAX_ROW_TEXT).trim()

        assertSplit(parseMarkdown("# $text").rows, text)
        assertSplit(parseMarkdown("```\n$text\n```").rows, text)
        assertTrue(parseMarkdown("# $text").rows.all { it.block is Heading })
    }

    @Test
    fun aLongItemKeepsItsMarkerOnTheFirstRow() {
        val text = "a ".repeat(MAX_ROW_TEXT).trim()
        val rows = parseMarkdown("- [ ] $text\n- b").rows

        assertEquals(Task(3, false), rows.first().task)
        assertEquals(listOf(0, 1, 0), rows.map { it.indent })
        assertEquals(listOf("•", null, "•"), rows.map { it.marker })
        assertEquals("b", rows.last().text())
    }

    @Test
    fun crlfSplitsBlocksLikeLf() {
        assertEquals(
            blocks("# a\n\nb\nc\n\n- d\n- e\n"),
            blocks("# a\r\n\r\nb\r\nc\r\n\r\n- d\r\n- e\r\n"),
        )
        assertEquals(blocks("a\n\nb"), blocks("a\r\rb"))
        assertEquals(blocks("a\n"), blocks("a\r"))
    }

    @Test
    fun anythingNestedDeeperThanTheLimitShowsAsTyped() {
        val depth = MAX_NESTING + 3
        val text = (0 until depth).joinToString("\n") { "  ".repeat(it) + "- [ ] item$it" }
        val rows = parseMarkdown(text).rows

        assertEquals(MAX_NESTING, rows.count { it.task != null })
        val rest = assertIs<Paragraph>(rows.last().block).spans.single().text
        assertEquals(3, rest.lines().size)
        assertEquals("- [ ] item$MAX_NESTING", rest.lines().first())
    }

    @Test
    fun aLineNestedTooDeeplyMakesTheNotePlainText() {
        val tooDeep = ">".repeat(MAX_NESTING + 1) + " a"
        assertEquals(
            listOf(Paragraph(listOf(Span("# b"))), Paragraph(listOf(Span(tooDeep)))),
            blocks("# b\n$tooDeep"),
        )
        val lists = "- ".repeat(MAX_NESTING + 1) + "a"
        assertEquals(listOf(Paragraph(listOf(Span(lists)))), blocks(lists))
        val numbers = "1. ".repeat(MAX_NESTING + 1) + "a"
        assertEquals(listOf(Paragraph(listOf(Span(numbers)))), blocks(numbers))
        val tabbed = "-\t".repeat(MAX_NESTING + 1) + "a"
        assertEquals(listOf(Paragraph(listOf(Span(tabbed)))), blocks(tabbed))
    }

    @Test
    fun nestingUpToTheLimitAndLongRulesAreNotTooDeep() {
        assertIs<Quote>(single(">".repeat(MAX_NESTING) + " a"))
        assertEquals(Rule, single("-".repeat(MAX_NESTING * 2)))
        // Marks further along a line nest nothing.
        assertEquals(
            ">".repeat(MAX_NESTING * 2),
            plainText("a" + ">".repeat(MAX_NESTING * 2)).drop(1),
        )
    }

    @Test
    fun aPathologicalMegabyteStaysPlainTextAndQuick() {
        for (text in listOf(">".repeat(MEGABYTE), "- ".repeat(MEGABYTE / 2))) {
            val elapsed = measureTime { assertIs<Paragraph>(single(text)) }
            assert(elapsed.inWholeSeconds < 2) { "took $elapsed" }
        }
    }

    @Test
    fun theScanSeesALoneCarriageReturnAsTheEndOfALine() {
        for (deep in listOf(">".repeat(100_000), "- ".repeat(100_000))) {
            assertEquals(
                listOf(Paragraph(listOf(Span("a"))), Paragraph(listOf(Span(deep)))),
                blocks("a\r$deep"),
            )
        }
    }

    @Test
    fun aTasksBoxDoesNotEndTheScanAsAListCanFollowIt() {
        val nested = "- [ ] - [x] - [X] a"
        assertEquals(3, parseMarkdown(nested).rows.count { it.task != null })

        // Small enough that the parser finishes, so that only the scan can make this plain text.
        val deep = "- [ ] ".repeat(1_000)
        assertEquals(listOf(Paragraph(listOf(Span(deep)))), blocks(deep))
        // Only a real box is skipped: this text is the line's content, so it cannot nest.
        val text = "- [y] " + "- ".repeat(MAX_NESTING)
        assertIs<ListBlock>(single(text))
    }

    @Test
    fun runningOutOfMemoryLeavesPlainText() {
        val document = parseMarkdown("# a\nb") { throw OutOfMemoryError("a huge note") }

        assertEquals(
            listOf(Paragraph(listOf(Span("# a"))), Paragraph(listOf(Span("b")))),
            document.blocks,
        )
    }

    @Test
    fun treesTooDeepToWalkFallBackToPlainText() {
        val count = MEGABYTE / 6
        val text = "*a ".repeat(count) + "b" + " c*".repeat(count)
        assertEquals(listOf(Paragraph(listOf(Span(text)))), parseMarkdown(text).blocks)
    }

    @Test
    fun aMegabyteOfEachCommonShapeParses() {
        val shapes =
            listOf(
                "word ".repeat(MEGABYTE / 5),
                "a".repeat(MEGABYTE),
                "- [ ] item\n".repeat(MEGABYTE / 11),
                "Some **bold** text and `code` here\n\n".repeat(MEGABYTE / 36),
                "|a|b|\n|-|-|\n" + "|1|2|\n".repeat(MEGABYTE / 6),
                "[a](".repeat(MEGABYTE / 4),
            )
        for (text in shapes) {
            assert(parseMarkdown(text).rows.isNotEmpty())
        }
    }

    @Test
    fun cancellingStopsTheParse() {
        assertFailsWith<CancellationException> {
            parseMarkdown("a\nb") { throw CancellationException("no longer needed") }
        }
    }

    @Test
    fun aParserFailureLeavesPlainText() {
        val document = parseMarkdown("# a\nb") { error("a parser bug") }

        assertEquals(
            listOf(Paragraph(listOf(Span("# a"))), Paragraph(listOf(Span("b")))),
            document.blocks,
        )
    }
}
