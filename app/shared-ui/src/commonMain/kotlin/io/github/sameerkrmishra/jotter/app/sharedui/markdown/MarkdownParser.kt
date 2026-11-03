package io.github.sameerkrmishra.jotter.app.sharedui.markdown

import io.github.sameerkrmishra.jotter.app.sharedui.reportUnexpected
import kotlinx.coroutines.CancellationException
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.html.entities.Entities
import org.intellij.markdown.html.entities.EntityConverter
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.LinkMap
import org.intellij.markdown.parser.MarkdownParser

/**
 * How deep lists and quotes go. Deeper text shows as it was typed. A line that opens more than
 * this at once makes the whole note plain text, because the parser's time grows with the square
 * of the depth: a 1 MB line of `>` runs it out of memory.
 */
internal const val MAX_NESTING = 32

private val HEADINGS =
    mapOf(
        MarkdownElementTypes.ATX_1 to 1,
        MarkdownElementTypes.ATX_2 to 2,
        MarkdownElementTypes.ATX_3 to 3,
        MarkdownElementTypes.ATX_4 to 4,
        MarkdownElementTypes.ATX_5 to 5,
        MarkdownElementTypes.ATX_6 to 6,
        MarkdownElementTypes.SETEXT_1 to 1,
        MarkdownElementTypes.SETEXT_2 to 2,
    )
private val HEADING_TEXT = setOf(MarkdownTokenTypes.ATX_CONTENT, MarkdownTokenTypes.SETEXT_CONTENT)
private val REFERENCE_LINKS =
    setOf(MarkdownElementTypes.FULL_REFERENCE_LINK, MarkdownElementTypes.SHORT_REFERENCE_LINK)

private const val BULLET = "•"
private const val FENCE_OPENING_LINE = 1
private const val CODE_INDENT = 4
private val MARKER_ENDS = setOf('-', '+', '*', '.', ')')
private val BOX_MARKS = setOf<Char?>(' ', 'x', 'X')

// An escape comes first, as `\&#128512;` is no entity.
private val ENTITY = Regex("""\\.|&(?:[a-zA-Z0-9]+|#[0-9]{1,8}|#[xX][a-fA-F0-9]{1,8});""")
private const val HEX_ENTITY_START = 3
private const val DECIMAL_ENTITY_START = 2
private const val LAST_BMP_CODE = 0xFFFFL
private const val LAST_CODE = 0x10FFFFL
private const val SUPPLEMENTARY_START = 0x10000L
private const val SURROGATE_BITS = 10
private const val LOW_SURROGATE_MASK = 0x3FFL
private const val REPLACEMENT_CHARACTER = "�"

/**
 * Reads [source] as GitHub Flavored Markdown. It never fails on any text: what it cannot read
 * comes back as plain text. It can take seconds on a large note, so it does not belong on the UI
 * thread. [cancellation] may stop it by throwing a [CancellationException], once per line or so.
 */
@Suppress("TooGenericExceptionCaught") // A parser bug on odd text must not cost the user the note.
internal fun parseMarkdown(
    source: String,
    cancellation: CancellationToken = CancellationToken.NonCancellable,
): MarkdownDocument =
    try {
        MarkdownDocument(source, read(source, cancellation))
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        reportUnexpected(e)
        MarkdownDocument(source, plainText(source))
    } catch (_: StackOverflowError) {
        // Thousands of nested emphasis marks, which are harmless but for the tree's depth.
        MarkdownDocument(source, plainText(source))
    } catch (e: OutOfMemoryError) {
        // The parse's own allocations, which are garbage now that it has unwound.
        reportUnexpected(e)
        MarkdownDocument(source, plainText(source))
    }

private fun read(
    source: String,
    cancellation: CancellationToken,
): List<Block> {
    val lines = LineEnds(source)
    val text: CharSequence = lines.text
    if (isTooDeep(lines.text)) return plainText(source)
    val root =
        MarkdownParser(
            GFMFlavourDescriptor(),
            false,
            cancellation,
        ).buildMarkdownTreeFromString(text)
    return TreeReader(lines, LinkMap.buildLinkMap(root, text)).blocks(root.children, depth = 0)
}

private fun plainText(source: String): List<Block> =
    source.lineSequence().map { Paragraph(listOf(Span(it))) }.toList()

// Takes the text with its line ends replaced, as a line that the parser sees is what counts.
private fun isTooDeep(text: String): Boolean {
    var markers = 0
    var index = 0
    while (index < text.length) {
        val char = text[index]
        when {
            char == '\n' -> markers = 0
            markers < 0 -> Unit
            isNesting(text, index) -> markers++
            // A task's box can be followed by another list on the same line.
            isBox(text, index) -> index += 2
            // The line's content starts here: nothing more on it can nest.
            !char.isWhitespace() && !char.isDigit() && char !in MARKER_ENDS -> markers = -1
        }
        if (markers > MAX_NESTING) return true
        index++
    }
    return false
}

private fun isNesting(
    text: String,
    index: Int,
): Boolean =
    text[index] == '>' ||
        text[index] in MARKER_ENDS &&
        text.getOrNull(index + 1).let { it == ' ' || it == '\t' }

private fun isBox(
    text: String,
    index: Int,
): Boolean =
    text[index] == '[' && text.getOrNull(index + 1) in BOX_MARKS && text.getOrNull(index + 2) == ']'

/**
 * The text as the parser needs it, as to the parser only `\n` ends a line, and how to get from an
 * offset in it back to the note's text. A `\r` that it does not know would hide blank lines.
 */
private class LineEnds(
    source: String,
) {
    // Where in text is a `\n` whose `\r` was dropped, in order.
    private val dropped = ArrayList<Int>()

    val text: String =
        buildString(source.length) {
            for (index in source.indices) {
                val char = source[index]
                when {
                    char != '\r' -> append(char)
                    source.getOrNull(index + 1) == '\n' -> dropped += length
                    else -> append('\n')
                }
            }
        }

    /** Where [offset] is in the note's text. It must not be a line's end, which is never found. */
    fun sourceOffset(offset: Int): Int = offset - dropped.binarySearch(offset) - 1
}

/** Turns the parser's tree into the plain model. */
@Suppress("TooManyFunctions") // One function per kind of node, in one walk of the tree.
private class TreeReader(
    private val lines: LineEnds,
    private val links: LinkMap,
) {
    fun blocks(
        nodes: List<ASTNode>,
        depth: Int,
    ): List<Block> = nodes.mapNotNull { block(it, depth) }

    private fun block(
        node: ASTNode,
        depth: Int,
    ): Block? {
        val level = HEADINGS[node.type]
        return when {
            level != null -> Heading(level, headingSpans(node))
            node.type == MarkdownElementTypes.PARAGRAPH -> Paragraph(spans(node.children))
            node.type == MarkdownTokenTypes.HORIZONTAL_RULE -> Rule
            node.type == GFMTokenTypes.ALERT_TITLE -> rawParagraph(node)
            node.isContainer -> container(node, depth)
            else -> leaf(node)
        }
    }

    private val ASTNode.isContainer
        get() =
            type == MarkdownElementTypes.BLOCK_QUOTE ||
                type == GFMElementTypes.ALERT ||
                type == MarkdownElementTypes.UNORDERED_LIST ||
                type == MarkdownElementTypes.ORDERED_LIST

    private fun container(
        node: ASTNode,
        depth: Int,
    ): Block =
        when {
            depth >= MAX_NESTING -> rawParagraph(node)
            node.type == MarkdownElementTypes.BLOCK_QUOTE || node.type == GFMElementTypes.ALERT ->
                Quote(blocks(node.children, depth + 1))
            else -> list(node, depth)
        }

    private fun leaf(node: ASTNode): Block? =
        when {
            node.type == MarkdownElementTypes.CODE_FENCE -> CodeBlock(fenced(node))
            node.type == MarkdownElementTypes.CODE_BLOCK -> CodeBlock(indented(node))
            node.type == GFMElementTypes.TABLE -> CodeBlock(source(node))
            node.type == MarkdownElementTypes.LINK_DEFINITION -> null
            // Raw HTML, and whatever else has no model of its own, as it was typed.
            node.children.isNotEmpty() -> rawParagraph(node)
            // Line ends, and the markers of a quote or a list.
            else -> null
        }

    private fun rawParagraph(node: ASTNode) = Paragraph(listOf(Span(source(node))))

    private fun source(node: ASTNode) = lines.text.substring(node.startOffset, node.endOffset)

    private fun headingSpans(node: ASTNode) =
        spans(node.children.filter { it.type in HEADING_TEXT }.flatMap { it.children })

    private fun fenced(node: ASTNode): String {
        val codeLines = mutableListOf(StringBuilder())
        for (child in node.children) {
            when (child.type) {
                MarkdownTokenTypes.EOL -> codeLines += StringBuilder()
                MarkdownTokenTypes.CODE_FENCE_CONTENT -> codeLines.last().append(source(child))
            }
        }
        val isClosed = node.children.last().type == MarkdownTokenTypes.CODE_FENCE_END
        return codeLines
            .drop(FENCE_OPENING_LINE)
            .let { if (isClosed) it.dropLast(1) else it }
            .joinToString("\n")
    }

    private fun indented(node: ASTNode): String =
        node.children
            .filter { it.type == MarkdownTokenTypes.CODE_LINE }
            .joinToString("\n") { line ->
                val code = source(line)
                if (code.startsWith('\t')) {
                    code.drop(1)
                } else {
                    code.drop(code.take(CODE_INDENT).takeWhile { it == ' ' }.length)
                }
            }

    private fun list(
        node: ASTNode,
        depth: Int,
    ): ListBlock {
        val items = node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
        val numbers = items.map(::numberOf)
        val start = numbers.first()?.dropLast(1)?.toLong() ?: 0L
        return ListBlock(
            items.mapIndexed { index, item ->
                val number = numbers[index]
                ListItem(
                    marker = number?.let { "${start + index}${it.last()}" } ?: BULLET,
                    task = task(item),
                    blocks = blocks(item.children, depth + 1),
                )
            },
        )
    }

    private fun numberOf(item: ASTNode): String? =
        item.children
            .firstOrNull { it.type == MarkdownTokenTypes.LIST_NUMBER }
            ?.let { source(it).trim() }

    private fun task(item: ASTNode): Task? =
        item.children.firstOrNull { it.type == GFMTokenTypes.CHECK_BOX }?.let { box ->
            val offset = box.startOffset + 1
            Task(lines.sourceOffset(offset), lines.text[offset] != ' ')
        }

    private fun spans(nodes: List<ASTNode>): List<Span> {
        val out = SpanWriter()
        inlines(nodes, SpanLook(), out)
        return out.spans()
    }

    private fun inlines(
        nodes: List<ASTNode>,
        look: SpanLook,
        out: SpanWriter,
    ) {
        nodes.forEach { inline(it, look, out) }
    }

    private fun inline(
        node: ASTNode,
        look: SpanLook,
        out: SpanWriter,
    ) {
        when (node.type) {
            MarkdownTokenTypes.EOL -> out.lineBreak(look)
            MarkdownTokenTypes.WHITE_SPACE -> out.space(source(node), look)
            // A line break's text. The line's end is the next token.
            MarkdownTokenTypes.HARD_LINE_BREAK, MarkdownTokenTypes.BLOCK_QUOTE -> Unit
            MarkdownElementTypes.CODE_SPAN -> out.text(codeSpan(node), look.copy(isCode = true))
            MarkdownElementTypes.INLINE_LINK -> link(node, look, out)
            MarkdownElementTypes.AUTOLINK -> autolink(source(node).trim('<', '>'), look, out)
            GFMTokenTypes.GFM_AUTOLINK -> autolink(source(node), look, out)
            in REFERENCE_LINKS -> referenceLink(node, look, out)
            else -> styled(node, look, out)
        }
    }

    private fun styled(
        node: ASTNode,
        look: SpanLook,
        out: SpanWriter,
    ) {
        when (node.type) {
            MarkdownElementTypes.EMPH ->
                inlines(delimited(node), look.copy(isItalic = true), out)
            MarkdownElementTypes.STRONG ->
                inlines(delimited(node), look.copy(isBold = true), out)
            GFMElementTypes.STRIKETHROUGH ->
                inlines(delimited(node), look.copy(isStruck = true), out)
            // Images and raw HTML are plain text, which is what keeps the note off the network.
            MarkdownTokenTypes.HTML_TAG -> out.text(source(node), look)
            else -> plain(node, look, out)
        }
    }

    private fun plain(
        node: ASTNode,
        look: SpanLook,
        out: SpanWriter,
    ) {
        val text = source(node)
        val isToken = node.children.isEmpty()
        out.text(if (isToken) decoded(text) else text, look)
    }

    private fun delimited(node: ASTNode): List<ASTNode> {
        val delimiter = node.children.first().type
        return node.children
            .dropWhile {
                it.type == delimiter
            }.dropLastWhile { it.type == delimiter }
    }

    private fun codeSpan(node: ASTNode): String {
        val delimiters = node.children
        val code =
            lines.text
                .substring(delimiters.first().endOffset, delimiters.last().startOffset)
                .replace('\n', ' ')
        val isPadded = code.length > 2 && code.first() == ' ' && code.last() == ' '
        return if (isPadded && code.isNotBlank()) code.substring(1, code.length - 1) else code
    }

    private fun link(
        node: ASTNode,
        look: SpanLook,
        out: SpanWriter,
    ) {
        val text = node.child(MarkdownElementTypes.LINK_TEXT)
        val destination =
            node.children.firstOrNull {
                it.type ==
                    MarkdownElementTypes.LINK_DESTINATION
            }
        linked(text.children, destination?.let(::destination).orEmpty(), look, out)
    }

    private fun referenceLink(
        node: ASTNode,
        look: SpanLook,
        out: SpanWriter,
    ) {
        val label = node.child(MarkdownElementTypes.LINK_LABEL)
        val definition = links.getLinkInfo(source(label))?.node
        if (definition == null) {
            // `[like this]` with nothing defined is only text. So is most of a checklist's text.
            inlines(node.children.flatMap { it.children }, look, out)
        } else {
            val text = node.children.first().children
            val target = destination(definition.child(MarkdownElementTypes.LINK_DESTINATION))
            linked(text, target, look, out)
        }
    }

    private fun linked(
        bracketed: List<ASTNode>,
        url: String,
        look: SpanLook,
        out: SpanWriter,
    ) {
        val linkLook = look.copy(url = url)
        inlines(bracketed.drop(1).dropLast(1), linkLook, out)
        if (url.isNotEmpty()) out.text(" ($url)", linkLook)
    }

    private fun autolink(
        url: String,
        look: SpanLook,
        out: SpanWriter,
    ) {
        out.text(url, look.copy(url = look.url ?: url))
    }

    private fun ASTNode.child(type: IElementType) = children.first { it.type == type }

    private fun destination(node: ASTNode): String =
        decoded(source(node).removeSurrounding("<", ">"))

    // The converter's result is HTML, so it escapes these, after which every `&` is an escape.
    private fun decoded(text: String): String =
        EntityConverter
            .replaceEntities(withSupplementaryEntities(text), true, true)
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
}

// The converter cuts an entity's code down to one Char, so these never reach it.
private fun withSupplementaryEntities(text: String): String =
    ENTITY.replace(text) { match ->
        val code = entityCode(match.value)
        if (code == null || code <= LAST_BMP_CODE) match.value else supplementary(code)
    }

private fun entityCode(entity: String): Long? {
    val digits = entity.dropLast(1)
    return when {
        entity.startsWith("&#x", ignoreCase = true) ->
            digits.substring(HEX_ENTITY_START).toLong(radix = 16)
        entity.startsWith("&#") -> digits.substring(DECIMAL_ENTITY_START).toLong()
        else -> Entities.map[entity]?.toLong()
    }
}

private fun supplementary(code: Long): String {
    if (code > LAST_CODE) return REPLACEMENT_CHARACTER
    val offset = code - SUPPLEMENTARY_START
    val high = Char.MIN_HIGH_SURROGATE + (offset shr SURROGATE_BITS).toInt()
    val low = Char.MIN_LOW_SURROGATE + (offset and LOW_SURROGATE_MASK).toInt()
    return "$high$low"
}

/** Collects text into as few [Span]s as its looks allow. */
private class SpanWriter {
    private val spans = mutableListOf<Span>()
    private val run = StringBuilder()
    private var look = SpanLook()
    private var isAtLineStart = true

    fun text(
        text: String,
        look: SpanLook,
    ) {
        if (look != this.look) flush()
        this.look = look
        run.append(text)
        isAtLineStart = false
    }

    /** Space at the start of a line is the text's indent in the source, not part of the text. */
    fun space(
        text: String,
        look: SpanLook,
    ) {
        if (!isAtLineStart) text(text, look)
    }

    /** Keeps a single line break, unlike most Markdown: notes from Keep are written in lines. */
    fun lineBreak(look: SpanLook) {
        text("\n", look)
        isAtLineStart = true
    }

    fun spans(): List<Span> {
        flush()
        return spans
    }

    private fun flush() {
        if (run.isNotEmpty()) spans += Span(run.toString(), look)
        run.clear()
    }
}
