package io.github.sameerkrmishra.jotter.app.sharedui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

internal const val MARKDOWN_PREVIEW = "markdown-preview"

private val indentStep = 24.dp
private val rowGap = 4.dp
private val barWidth = 2.dp
private val codePadding = 8.dp

/** The test tag of the checkbox of the task at [offset]. */
internal fun taskTag(offset: Int) = "task-$offset"

/** The note as formatted text. The only thing in it that does anything is a task's checkbox. */
@Composable
internal fun MarkdownPreview(
    document: MarkdownDocument,
    onToggle: (Task) -> Unit,
) {
    LazyColumn(
        Modifier.testTag(MARKDOWN_PREVIEW),
        verticalArrangement = Arrangement.spacedBy(rowGap),
    ) {
        items(document.rows) { PreviewRow(it, onToggle) }
    }
}

@Composable
private fun PreviewRow(
    row: Row,
    onToggle: (Task) -> Unit,
) {
    val barColor = MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier
            .fillMaxWidth()
            .quoteBars(row.quoteBars, barColor)
            .padding(start = indentStep * row.indent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val task = row.task
        if (task != null) {
            Checkbox(
                checked = task.isChecked,
                onCheckedChange = { onToggle(task) },
                modifier = Modifier.testTag(taskTag(task.offset)),
            )
        } else if (row.marker != null) {
            Text(row.marker, Modifier.widthIn(min = indentStep))
        }
        LeafView(row.block, Modifier.weight(1f))
    }
}

private fun Modifier.quoteBars(
    levels: List<Int>,
    color: Color,
) = drawBehind {
    for (level in levels) {
        val x = indentStep.toPx() * level + barWidth.toPx()
        drawLine(color, Offset(x, 0f), Offset(x, size.height), barWidth.toPx())
    }
}

@Composable
private fun LeafView(
    block: LeafBlock,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val links = colors.primary
    val code = colors.surfaceVariant
    when (block) {
        is Heading ->
            Text(
                annotated(block.spans, links, code),
                modifier,
                style = headingStyle(block),
            )
        is Paragraph -> Text(annotated(block.spans, links, code), modifier)
        is CodeBlock ->
            Text(
                block.text,
                modifier.background(code).padding(codePadding),
                fontFamily = FontFamily.Monospace,
            )
        Rule -> HorizontalDivider(modifier)
    }
}

@Composable
private fun headingStyle(heading: Heading): TextStyle {
    val type = MaterialTheme.typography
    val styles = listOf(type.headlineMedium, type.headlineSmall, type.titleLarge, type.titleMedium)
    return styles.getOrNull(heading.level - 1) ?: type.titleSmall
}

/** [spans] as styled text, in which a link's colour is [links] and code's background is [code]. */
internal fun annotated(
    spans: List<Span>,
    links: Color,
    code: Color,
): AnnotatedString =
    buildAnnotatedString {
        for (span in spans) {
            withStyle(spanStyle(span.look, links, code)) { append(span.text) }
        }
    }

private fun spanStyle(
    look: SpanLook,
    links: Color,
    code: Color,
): SpanStyle {
    val decorations =
        listOfNotNull(
            TextDecoration.Underline.takeIf { look.url != null },
            TextDecoration.LineThrough.takeIf { look.isStruck },
        )
    return SpanStyle(
        color = if (look.url != null) links else Color.Unspecified,
        fontWeight = if (look.isBold) FontWeight.Bold else null,
        fontStyle = if (look.isItalic) FontStyle.Italic else null,
        fontFamily = if (look.isCode) FontFamily.Monospace else null,
        background = if (look.isCode) code else Color.Unspecified,
        textDecoration = TextDecoration.combine(decorations),
    )
}
