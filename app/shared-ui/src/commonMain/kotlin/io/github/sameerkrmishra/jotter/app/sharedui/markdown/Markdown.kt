package io.github.sameerkrmishra.jotter.app.sharedui.markdown

/** A note's body as blocks and inline spans, with nothing of Compose or of the parser in it. */
internal data class MarkdownDocument(
    /** The exact text that was parsed, which every [Task.offset] refers to. */
    val source: String,
    val blocks: List<Block>,
) {
    /** The blocks in reading order, one [Row] to a line of the screen's list. */
    val rows: List<Row> = flatten(blocks)
}

/** A part of the text that stands on its own. */
internal sealed interface Block

/** A block with no blocks inside it. */
internal sealed interface LeafBlock : Block

/** A heading of [level] 1 to 6. */
internal data class Heading(
    val level: Int,
    val spans: List<Span>,
) : LeafBlock

/** Running text. Raw HTML, and what the preview does not format, is here as it was typed. */
internal data class Paragraph(
    val spans: List<Span>,
) : LeafBlock

/** Text in a fixed-width font, line for line. */
internal data class CodeBlock(
    val text: String,
) : LeafBlock

/** A horizontal rule. */
internal data object Rule : LeafBlock

/** A block quote. */
internal data class Quote(
    val blocks: List<Block>,
) : Block

/** A bullet or ordered list. */
internal data class ListBlock(
    val items: List<ListItem>,
) : Block

/** One item of a [ListBlock], with what shows as its [marker], such as "•" or "2.". */
internal data class ListItem(
    val marker: String,
    val task: Task?,
    val blocks: List<Block>,
)

/** The checkbox of a task list item. */
internal data class Task(
    /** Where in [MarkdownDocument.source] the character between the brackets is. */
    val offset: Int,
    val isChecked: Boolean,
)

/** A piece of inline text in one look. */
internal data class Span(
    val text: String,
    val look: SpanLook = SpanLook(),
)

/** How a [Span] looks. */
internal data class SpanLook(
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val isStruck: Boolean = false,
    val isCode: Boolean = false,
    /** Where a link goes. Nothing opens it: the text shows it. */
    val url: String? = null,
)

/**
 * One line of the screen's list: a [block] and what it is nested in. Rows make the whole preview
 * one lazy list, so that a long list costs only what is on screen, however it is nested.
 */
internal data class Row(
    val block: LeafBlock,
    /** How many levels of list or quote it is in. */
    val indent: Int,
    /** The levels of [indent] that are quotes, which show a bar. */
    val quoteBars: List<Int>,
    val marker: String? = null,
    val task: Task? = null,
)

/** [source] with the box of [task] ticked or cleared, and no other character changed. */
internal fun toggled(
    source: String,
    task: Task,
): String = source.replaceRange(task.offset, task.offset + 1, if (task.isChecked) " " else "x")

/**
 * The most text in one [Row]. Laying out 1 MB of text stops the UI thread for 0.7 s, so a longer
 * block is split over several rows, of which only those on screen are laid out.
 */
internal const val MAX_ROW_TEXT = 4_096

private fun flatten(blocks: List<Block>): List<Row> =
    buildList { addRows(blocks, indent = 0, quoteBars = emptyList()) }

private fun LeafBlock.pieces(): List<LeafBlock> =
    when (this) {
        is Heading -> pieces(spans).map { Heading(level, it) }
        is Paragraph -> pieces(spans).map(::Paragraph)
        is CodeBlock -> textPieces(text).map(::CodeBlock)
        Rule -> listOf(this)
    }

private fun pieces(spans: List<Span>): List<List<Span>> {
    if (spans.sumOf { it.text.length } <= MAX_ROW_TEXT) return listOf(spans)
    val pieces = mutableListOf<List<Span>>()
    val piece = mutableListOf<Span>()
    var length = 0
    for (span in spans) {
        for (text in textPieces(span.text)) {
            if (length + text.length > MAX_ROW_TEXT) {
                pieces += piece.toList()
                piece.clear()
                length = 0
            }
            piece += Span(text, span.look)
            length += text.length
        }
    }
    return pieces + listOf(piece)
}

// Ends a piece after a space if there is one, and never inside a surrogate pair.
private fun textPieces(text: String): List<String> {
    val pieces = mutableListOf<String>()
    var start = 0
    while (text.length - start > MAX_ROW_TEXT) {
        val piece = text.substring(start, start + MAX_ROW_TEXT)
        val space = piece.lastIndexOf(' ')
        val length =
            when {
                space >= 0 -> space + 1
                piece.last().isHighSurrogate() -> piece.length - 1
                else -> piece.length
            }
        pieces += text.substring(start, start + length)
        start += length
    }
    return pieces + text.substring(start)
}

private fun MutableList<Row>.addRows(
    blocks: List<Block>,
    indent: Int,
    quoteBars: List<Int>,
) {
    for (block in blocks) {
        when (block) {
            is LeafBlock -> block.pieces().forEach { add(Row(it, indent, quoteBars)) }
            is Quote -> addRows(block.blocks, indent + 1, quoteBars + indent)
            is ListBlock -> block.items.forEach { addItem(it, indent, quoteBars) }
        }
    }
}

// The marker goes on the item's first line. If that is a nested block, it gets a line of its own.
private fun MutableList<Row>.addItem(
    item: ListItem,
    indent: Int,
    quoteBars: List<Int>,
) {
    val first = (item.blocks.firstOrNull() as? LeafBlock)?.pieces()
    val line = first?.first() ?: Paragraph(emptyList())
    add(Row(line, indent, quoteBars, item.marker, item.task))
    val rest = if (first == null) item.blocks else first.drop(1) + item.blocks.drop(1)
    addRows(rest, indent + 1, quoteBars)
}
