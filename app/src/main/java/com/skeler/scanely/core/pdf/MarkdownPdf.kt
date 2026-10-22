package com.skeler.scanely.core.pdf

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import com.skeler.scanely.core.text.MarkdownParser
import com.skeler.scanely.core.text.MathText
import com.skeler.scanely.core.text.MdBlock

/**
 * Draws the parsed Markdown blocks — the same ones the preview composes — onto PDF pages, so the
 * file carries the headings, emphasis, lists and table grids the user is looking at.
 */
internal object MarkdownPdf {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 40
    private const val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN
    private const val CONTENT_BOTTOM = PAGE_HEIGHT - MARGIN

    private const val BODY_SIZE = 12f
    private const val CODE_SIZE = 11f
    private const val LINE_EXTRA = 4f
    private const val BLOCK_GAP = 8f
    private const val LIST_GAP = 3f
    private const val HEADING_GAP = 6f
    private const val INDENT_STEP = 10f
    private const val BULLET_WIDTH = 16f
    private const val NUMBER_WIDTH = 24f
    private const val CELL_PADDING = 6f
    private const val CODE_PADDING = 8f
    private const val QUOTE_BAR = 3f
    private const val QUOTE_GAP = 10f

    /**
     * Ceiling on the width one column may reserve for its longest word, so a single unbreakable
     * string — a URL, a hash — cannot starve every other column to fit itself.
     */
    private const val MAX_COLUMN_FLOOR = 0.5f

    private val WHITESPACE = Regex("\\s+")

    private const val TEXT_COLOR = Color.BLACK
    private const val MUTED_COLOR = 0xFF444444.toInt()
    private const val LINK_COLOR = 0xFF1A5FB4.toInt()
    private const val RULE_COLOR = 0xFF999999.toInt()
    private const val BORDER_COLOR = 0xFF555555.toInt()
    private const val FILL_COLOR = 0xFFEEEEEE.toInt()

    /** Caller owns and must close the returned document. */
    fun render(source: String): PdfDocument {
        val document = PdfDocument()
        try {
            val pager = Pager(document)
            MarkdownParser.parse(source).forEach { block -> draw(pager, block) }
            pager.finish()
            if (document.pages.isEmpty()) pager.blankPage()
        } catch (t: Throwable) {
            document.close()
            throw t
        }
        return document
    }

    private fun draw(pager: Pager, block: MdBlock) {
        when (block) {
            is MdBlock.Heading -> {
                pager.advance(HEADING_GAP)
                flow(pager, layout(spans(block.text), heading(block.level), CONTENT_WIDTH), MARGIN.toFloat())
                pager.advance(BLOCK_GAP)
            }

            is MdBlock.Paragraph -> {
                flow(pager, layout(spans(block.lines.joinToString("\n")), body(), CONTENT_WIDTH), MARGIN.toFloat())
                pager.advance(BLOCK_GAP)
            }

            is MdBlock.Bullet -> {
                val marker = when (block.checked) {
                    true -> "☑"
                    false -> "☐"
                    null -> "•"
                }
                listItem(pager, marker, block.text, block.indent, BULLET_WIDTH)
            }

            is MdBlock.Numbered ->
                listItem(pager, block.marker, block.text, block.indent, NUMBER_WIDTH)

            is MdBlock.Quote -> quote(pager, block)
            is MdBlock.Code -> code(pager, block)
            is MdBlock.Table -> table(pager, block)

            is MdBlock.Equation -> {
                val paint = body().apply {
                    typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                }
                flow(pager, layout(MathText.prettify(block.latex), paint, CONTENT_WIDTH), MARGIN.toFloat())
                pager.advance(BLOCK_GAP)
            }

            is MdBlock.Image -> {
                val paint = body(italic = true, color = MUTED_COLOR)
                flow(pager, layout(block.label, paint, CONTENT_WIDTH), MARGIN.toFloat())
                pager.advance(BLOCK_GAP)
            }

            MdBlock.Divider -> divider(pager)
        }
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
                6f,
                6f,
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

    private fun table(pager: Pager, table: MdBlock.Table) {
        val columns = table.header.size
        if (columns == 0) return
        val widths = columnWidths(table)
        val fill = FILL_COLOR

        val header = cells(table.header, widths, bold = true)
        row(pager, header, widths, fill)
        table.rows.forEach { data ->
            val laid = cells(data, widths, bold = false)
            // A row never splits; carrying the header over keeps the grid readable.
            if (pager.needsBreak(height(laid))) {
                pager.finish()
                row(pager, header, widths, fill)
            }
            row(pager, laid, widths, fill = null)
        }
        pager.advance(BLOCK_GAP)
    }

    /**
     * Columns share the page in proportion to the text they hold, but never below the width of
     * their longest word.
     *
     * Both halves matter. Sizing on character counts treated `$1,200` and `mmmmmm` as equal, and
     * proportional sizing alone let a long neighbour squeeze a column under its own header —
     * which is how a four-column invoice broke `Amount` across two lines.
     */
    private fun columnWidths(table: MdBlock.Table): List<Float> {
        val columns = table.header.size
        val desired = FloatArray(columns)
        val floor = FloatArray(columns)

        fun measure(row: List<String>, paint: TextPaint) {
            for (column in 0 until columns) {
                val text = MarkdownParser.plain(row.getOrElse(column) { "" })
                desired[column] = maxOf(desired[column], paint.measureText(text))
                floor[column] = maxOf(floor[column], longestWord(text, paint))
            }
        }

        measure(table.header, body(bold = true))
        val bodyPaint = body()
        table.rows.forEach { measure(it, bodyPaint) }

        val padding = 2 * CELL_PADDING
        val cap = CONTENT_WIDTH * MAX_COLUMN_FLOOR
        return distribute(
            desired.map { it + padding },
            floor.map { (it + padding).coerceAtMost(cap) }
        )
    }

    private fun longestWord(text: String, paint: TextPaint): Float =
        text.split(WHITESPACE).maxOfOrNull { paint.measureText(it) } ?: 0f

    /**
     * Gives each column its proportional share, pins any that landed under its floor, and splits
     * what is left among the rest — repeatedly, since pinning one column shrinks the pool the
     * others draw from.
     */
    private fun distribute(desired: List<Float>, floor: List<Float>): List<Float> {
        val widths = desired.toMutableList()
        val pinned = BooleanArray(desired.size)

        repeat(desired.size) {
            val taken = widths.filterIndexed { column, _ -> pinned[column] }.sum()
            val free = CONTENT_WIDTH - taken
            val flexible = desired.filterIndexed { column, _ -> !pinned[column] }.sum()
            if (flexible > 0f) {
                var pinnedAny = false
                desired.indices.filterNot { pinned[it] }.forEach { column ->
                    val share = free * desired[column] / flexible
                    if (share < floor[column]) {
                        widths[column] = floor[column]
                        pinned[column] = true
                        pinnedAny = true
                    } else {
                        widths[column] = share
                    }
                }
                if (!pinnedAny) return widths
            }
        }

        // Every column is pinned and they still overrun the page: no arrangement avoids a break,
        // so scale to fit and let the text wrap.
        val total = widths.sum()
        return if (total > CONTENT_WIDTH) widths.map { it * CONTENT_WIDTH / total } else widths
    }

    private fun cells(cells: List<String>, widths: List<Float>, bold: Boolean): List<StaticLayout> {
        val paint = body(bold = bold)
        return widths.mapIndexed { column, width ->
            layout(
                spans(cells.getOrElse(column) { "" }),
                paint,
                (width - 2 * CELL_PADDING).toInt().coerceAtLeast(1)
            )
        }
    }

    private fun height(cells: List<StaticLayout>): Float =
        cells.maxOf { it.height }.toFloat() + 2 * CELL_PADDING

    private fun row(pager: Pager, cells: List<StaticLayout>, widths: List<Float>, fill: Int?) {
        val height = height(cells)
        val canvas = pager.reserve(height)
        val top = pager.y
        val border = stroke(BORDER_COLOR)

        if (fill != null) {
            canvas.drawRect(
                MARGIN.toFloat(),
                top,
                MARGIN + widths.sum(),
                top + height,
                fill(fill)
            )
        }
        canvas.drawRect(MARGIN.toFloat(), top, MARGIN + widths.sum(), top + height, border)

        var x = MARGIN.toFloat()
        cells.forEachIndexed { column, cell ->
            if (column > 0) canvas.drawLine(x, top, x, top + height, border)
            canvas.save()
            canvas.translate(x + CELL_PADDING, top + CELL_PADDING)
            cell.draw(canvas)
            canvas.restore()
            x += widths[column]
        }
        pager.advance(height)
    }

    /** Draws [content] from the cursor down, breaking pages between its lines. */
    private fun flow(
        pager: Pager,
        content: StaticLayout,
        x: Float,
        decorate: ((Canvas, Float, Float) -> Unit)? = null
    ) {
        var line = 0
        while (line < content.lineCount) {
            val canvas = pager.reserve(lineHeight(content, line))
            val top = content.getLineTop(line)
            var end = line
            while (end < content.lineCount &&
                content.getLineBottom(end) - top <= pager.remaining
                ) end++
            // An oversized line still has to go somewhere.
            if (end == line) end = line + 1

            val height = (content.getLineBottom(end - 1) - top).toFloat()
            decorate?.invoke(canvas, pager.y, height)

            canvas.save()
            canvas.translate(x, pager.y - top)
            canvas.clipRect(0f, top.toFloat(), content.width.toFloat(), top + height)
            content.draw(canvas)
            canvas.restore()

            pager.advance(height)
            line = end
            if (line < content.lineCount) pager.finish()
        }
    }

    private fun lineHeight(content: StaticLayout, line: Int): Float =
        (content.getLineBottom(line) - content.getLineTop(line)).toFloat()

    private fun layout(text: CharSequence, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setLineSpacing(LINE_EXTRA, 1f)
            .setIncludePad(false)
            .build()

    private fun spans(text: String): CharSequence = SpannableStringBuilder().apply {
        MarkdownParser.parseInline(text).forEach { span ->
            val start = length
            append(span.text)
            val style = when {
                span.bold && span.italic -> Typeface.BOLD_ITALIC
                span.bold -> Typeface.BOLD
                span.italic -> Typeface.ITALIC
                else -> null
            }
            style?.let { mark(StyleSpan(it), start) }
            if (span.strike) mark(StrikethroughSpan(), start)
            if (span.underline || span.link != null) mark(UnderlineSpan(), start)
            if (span.link != null) mark(ForegroundColorSpan(LINK_COLOR), start)
            if (span.code) {
                mark(TypefaceSpan("monospace"), start)
                mark(BackgroundColorSpan(FILL_COLOR), start)
            }
        }
    }

    private fun SpannableStringBuilder.mark(span: Any, start: Int) =
        setSpan(span, start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

    private fun heading(level: Int): TextPaint = body(
        size = when (level) {
            1 -> 21f
            2 -> 18f
            3 -> 15f
            else -> 13f
        },
        bold = true
    )

    private fun body(
        size: Float = BODY_SIZE,
        bold: Boolean = false,
        italic: Boolean = false,
        color: Int = TEXT_COLOR
    ): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = Typeface.create(
            Typeface.SANS_SERIF,
            when {
                bold && italic -> Typeface.BOLD_ITALIC
                bold -> Typeface.BOLD
                italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
        )
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    private fun stroke(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private class Pager(private val document: PdfDocument) {

        private var page: PdfDocument.Page? = null
        private var number = 0

        var y = 0f
            private set

        val remaining: Float get() = CONTENT_BOTTOM - y

        fun canvas(): Canvas = (page ?: start()).canvas

        /** True when [height] no longer fits below the cursor on a page that already has content. */
        fun needsBreak(height: Float): Boolean =
            page != null && y > MARGIN && y + height > CONTENT_BOTTOM

        /** Opens the page [height] should land on, breaking first when it doesn't fit. */
        fun reserve(height: Float): Canvas {
            if (needsBreak(height)) finish()
            return canvas()
        }

        fun advance(dy: Float) {
            if (page != null) y += dy
        }

        fun finish() {
            page?.let { document.finishPage(it) }
            page = null
        }

        fun blankPage() {
            start()
            finish()
        }

        private fun start(): PdfDocument.Page {
            number++
            val started = document.startPage(
                PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, number).create()
            )
            started.canvas.drawColor(Color.WHITE)
            page = started
            y = MARGIN.toFloat()
            return started
        }
    }
}
