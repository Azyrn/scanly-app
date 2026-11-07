package com.skeler.scanely.core.pdf.markdown

import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.skeler.scanely.core.text.MarkdownParser
import com.skeler.scanely.core.text.MathText
import com.skeler.scanely.core.text.MdBlock

/**
 * Draws the parsed Markdown blocks — the same ones the preview composes — onto PDF pages, so the
 * file carries the headings, emphasis, lists and table grids the user is looking at.
 */
internal object MarkdownPdf {

    /** Caller owns and must close the returned document. */
    fun render(source: String): PdfDocument {
        val document = PdfDocument()
        // Closed on any failure, which still propagates; the caller owns it only once built.
        var built = false
        try {
            val pager = Pager(document)
            MarkdownParser.parse(source).forEach { block -> draw(pager, block) }
            pager.finish()
            if (document.pages.isEmpty()) pager.blankPage()
            built = true
        } finally {
            if (!built) document.close()
        }
        return document
    }

    private fun draw(pager: Pager, block: MdBlock) {
        when (block) {
            is MdBlock.Heading, is MdBlock.Paragraph, is MdBlock.Equation, is MdBlock.Image -> text(pager, block)

            is MdBlock.Bullet -> listItem(pager, bulletMarker(block), block.text, block.indent, BULLET_WIDTH)

            is MdBlock.Numbered ->
                listItem(pager, block.marker, block.text, block.indent, NUMBER_WIDTH)

            is MdBlock.Quote -> quote(pager, block)
            is MdBlock.Code -> code(pager, block)
            is MdBlock.Table -> drawTable(pager, block)
            MdBlock.Divider -> divider(pager)
        }
    }

    private fun bulletMarker(block: MdBlock.Bullet): String = when (block.checked) {
        true -> "☑"
        false -> "☐"
        null -> "•"
    }

    /** A block that is one run of text: heading, paragraph, equation or image label. */
    private fun text(pager: Pager, block: MdBlock) {
        if (block is MdBlock.Heading) pager.advance(HEADING_GAP)
        val content = when (block) {
            is MdBlock.Heading -> layout(spans(block.text), heading(block.level), CONTENT_WIDTH)
            is MdBlock.Paragraph -> layout(spans(block.lines.joinToString("\n")), body(), CONTENT_WIDTH)
            is MdBlock.Equation -> {
                val paint = body().apply {
                    typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                }
                layout(MathText.prettify(block.latex), paint, CONTENT_WIDTH)
            }
            is MdBlock.Image -> layout(block.label, body(italic = true, color = MUTED_COLOR), CONTENT_WIDTH)
            else -> return
        }
        flow(pager, content, MARGIN.toFloat())
        pager.advance(BLOCK_GAP)
    }

    private fun listItem(pager: Pager, marker: String, text: String, indent: Int, gutter: Float) {
        val left = MARGIN + indent * INDENT_STEP
        val paint = body()
        val width = (CONTENT_WIDTH - indent * INDENT_STEP - gutter).toInt().coerceAtLeast(1)
        val content = layout(spans(text), paint, width)

        pager.reserve(lineHeight(content, 0))
        pager.canvas().drawText(marker, left, pager.y - paint.ascent(), paint)
        flow(pager, content, left + gutter)
        pager.advance(LIST_GAP)
    }

    private fun quote(pager: Pager, block: MdBlock.Quote) {
        val left = MARGIN + QUOTE_BAR + QUOTE_GAP
        val width = (CONTENT_WIDTH - QUOTE_BAR - QUOTE_GAP).toInt()
        val paint = body(italic = true, color = MUTED_COLOR)
        val bar = fill(MUTED_COLOR)

        flow(pager, layout(spans(block.lines.joinToString("\n")), paint, width), left) { canvas, top, height ->
            canvas.drawRect(MARGIN.toFloat(), top, MARGIN + QUOTE_BAR, top + height, bar)
        }
        pager.advance(BLOCK_GAP)
    }

    private fun code(pager: Pager, block: MdBlock.Code) {
        val paint = body(size = CODE_SIZE).apply { typeface = Typeface.MONOSPACE }
        val width = (CONTENT_WIDTH - 2 * CODE_PADDING).toInt()
        val background = fill(FILL_COLOR)

        pager.advance(CODE_PADDING)
        flow(pager, layout(block.text, paint, width), MARGIN + CODE_PADDING) { canvas, top, height ->
            canvas.drawRoundRect(
                RectF(
                    MARGIN.toFloat(),
                    top - CODE_PADDING,
                    (MARGIN + CONTENT_WIDTH).toFloat(),
                    top + height + CODE_PADDING
                ),
                CODE_CORNER_RADIUS,
                CODE_CORNER_RADIUS,
                background
            )
        }
        pager.advance(CODE_PADDING + BLOCK_GAP)
    }

    private fun divider(pager: Pager) {
        pager.reserve(1f)
        pager.canvas().drawLine(
            MARGIN.toFloat(),
            pager.y,
            (MARGIN + CONTENT_WIDTH).toFloat(),
            pager.y,
            stroke(RULE_COLOR)
        )
        pager.advance(1f + BLOCK_GAP)
    }
}
