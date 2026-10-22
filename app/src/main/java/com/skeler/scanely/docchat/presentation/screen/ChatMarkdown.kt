package com.skeler.scanely.docchat.presentation.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.skeler.scanely.core.text.MarkdownParser
import com.skeler.scanely.core.text.MdBlock

/**
 * An answer written in Markdown, drawn as such: the app's own parser (the one PDF and HTML export
 * use) splits it into blocks and spans, and each becomes plain Compose text. Text with no Markdown
 * in it comes out as one paragraph, the same as before.
 */
@Composable
internal fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { MarkdownParser.parse(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block -> Block(block) }
    }
}

@Composable
private fun Block(block: MdBlock) {
    val body = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    when (block) {
        is MdBlock.Heading -> Text(
            inline(block.text),
            style = (
                if (block.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall
                ).copy(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        )
        is MdBlock.Paragraph -> Text(inline(block.lines.joinToString("\n")), style = body)
        is MdBlock.Bullet -> ListItem(
            marker = when (block.checked) {
                true -> "☑"
                false -> "☐"
                null -> "•"
            },
            text = block.text,
            level = block.indent / INDENT_SPACES,
            style = body
        )
        is MdBlock.Numbered -> ListItem(block.marker, block.text, block.indent / INDENT_SPACES, body)
        is MdBlock.Quote -> Quote(block.lines.joinToString("\n"), body)
        is MdBlock.Code -> CodeBlock(block.text)
        is MdBlock.Table -> TableBlock(block, body)
        is MdBlock.Equation -> CodeBlock(block.latex)
        is MdBlock.Image -> Text(block.label, style = body.copy(fontStyle = FontStyle.Italic))
        MdBlock.Divider -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
    }
}

@Composable
private fun ListItem(marker: String, text: String, level: Int, style: TextStyle) {
    Row(Modifier.padding(start = LIST_INDENT * level.coerceIn(0, MAX_LEVEL))) {
        Text(marker, style = style, modifier = Modifier.widthIn(min = MARKER_WIDTH).padding(end = 6.dp))
        Text(inline(text), style = style)
    }
}

@Composable
private fun Quote(text: String, style: TextStyle) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        Surface(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.width(3.dp).fillMaxHeight()) {}
        Text(
            inline(text),
            style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.padding(start = 10.dp)
        )
    }
}

@Composable
private fun CodeBlock(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp)) {
            Text(
                text.trimEnd(),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                softWrap = false
            )
        }
    }
}

/** Columns as wide as their cells (within limits); a wide table scrolls sideways, the chat does not. */
@Composable
private fun TableBlock(table: MdBlock.Table, style: TextStyle) {
    val cell = style.merge(MaterialTheme.typography.bodyMedium)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            TableRow(table.header, cell.copy(fontWeight = FontWeight.SemiBold))
            HorizontalDivider()
            table.rows.forEach { row -> TableRow(row, cell) }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, style: TextStyle) {
    Row {
        cells.forEach { text ->
            Text(
                inline(text),
                style = style,
                modifier = Modifier.width(CELL_WIDTH).padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

/** One line's bold, italic, struck, underlined, code and linked parts. */
@Composable
private fun inline(source: String): AnnotatedString {
    val colors = MaterialTheme.colorScheme
    return remember(source, colors) {
        val link = TextLinkStyles(SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline))
        buildAnnotatedString {
            MarkdownParser.parseInline(source).forEach { span ->
                val decoration = TextDecoration.combine(
                    listOfNotNull(
                        TextDecoration.LineThrough.takeIf { span.strike },
                        TextDecoration.Underline.takeIf { span.underline }
                    )
                )
                val style = SpanStyle(
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                    textDecoration = decoration,
                    fontFamily = if (span.code) FontFamily.Monospace else null,
                    background = if (span.code) colors.surfaceContainerHigh else Color.Unspecified
                )
                val url = span.link?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                if (url != null) {
                    withLink(LinkAnnotation.Url(url, link)) { withStyle(style) { append(span.text) } }
                } else {
                    withStyle(style) { append(span.text) }
                }
            }
        }
    }
}

private const val INDENT_SPACES = 2
private const val MAX_LEVEL = 3
private val LIST_INDENT = 16.dp
private val MARKER_WIDTH = 18.dp
private val CELL_WIDTH = 120.dp
