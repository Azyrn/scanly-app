package com.skeler.scanely.core.pdf.markdown

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import androidx.core.graphics.withTranslation
import com.skeler.scanely.core.text.MarkdownParser
import com.skeler.scanely.core.text.MdSpan

// Page geometry, type and colour shared by the Markdown PDF drawing code.
internal const val PAGE_WIDTH = 595
internal const val PAGE_HEIGHT = 842
internal const val MARGIN = 40
internal const val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN
internal const val CONTENT_BOTTOM = PAGE_HEIGHT - MARGIN

internal const val BODY_SIZE = 12f
internal const val CODE_SIZE = 11f
internal const val LINE_EXTRA = 4f
internal const val BLOCK_GAP = 8f
internal const val LIST_GAP = 3f
internal const val HEADING_GAP = 6f
internal const val INDENT_STEP = 10f
internal const val BULLET_WIDTH = 16f
internal const val NUMBER_WIDTH = 24f
internal const val CELL_PADDING = 6f
internal const val CODE_PADDING = 8f
internal const val CODE_CORNER_RADIUS = 6f
internal const val QUOTE_BAR = 3f
internal const val QUOTE_GAP = 10f

/**
 * Ceiling on the width one column may reserve for its longest word, so a single unbreakable
 * string — a URL, a hash — cannot starve every other column to fit itself.
 */
internal const val MAX_COLUMN_FLOOR = 0.5f

internal val WHITESPACE = Regex("\\s+")

internal const val TEXT_COLOR = Color.BLACK
internal const val MUTED_COLOR = 0xFF444444.toInt()
internal const val LINK_COLOR = 0xFF1A5FB4.toInt()
internal const val RULE_COLOR = 0xFF999999.toInt()
internal const val BORDER_COLOR = 0xFF555555.toInt()
internal const val FILL_COLOR = 0xFFEEEEEE.toInt()

/** Draws [content] from the cursor down, breaking pages between its lines. */
internal fun flow(
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

        canvas.withTranslation(x, pager.y - top) {
            clipRect(0f, top.toFloat(), content.width.toFloat(), top + height)
            content.draw(this)
        }

        pager.advance(height)
        line = end
        if (line < content.lineCount) pager.finish()
    }
}

internal fun lineHeight(content: StaticLayout, line: Int): Float =
    (content.getLineBottom(line) - content.getLineTop(line)).toFloat()

internal fun layout(text: CharSequence, paint: TextPaint, width: Int): StaticLayout =
    StaticLayout.Builder
        .obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
        .setLineSpacing(LINE_EXTRA, 1f)
        .setIncludePad(false)
        .build()

internal fun spans(text: String): CharSequence = SpannableStringBuilder().apply {
    MarkdownParser.parseInline(text).forEach { span ->
        val start = length
        append(span.text)
        markStyles(span, start)
    }
}

private fun SpannableStringBuilder.markStyles(span: MdSpan, start: Int) {
    styleOf(span)?.let { mark(StyleSpan(it), start) }
    if (span.strike) mark(StrikethroughSpan(), start)
    if (span.underline || span.link != null) mark(UnderlineSpan(), start)
    if (span.link != null) mark(ForegroundColorSpan(LINK_COLOR), start)
    if (span.code) {
        mark(TypefaceSpan("monospace"), start)
        mark(BackgroundColorSpan(FILL_COLOR), start)
    }
}

private fun styleOf(span: MdSpan): Int? = when {
    span.bold && span.italic -> Typeface.BOLD_ITALIC
    span.bold -> Typeface.BOLD
    span.italic -> Typeface.ITALIC
    else -> null
}

private fun SpannableStringBuilder.mark(span: Any, start: Int) =
    setSpan(span, start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

internal fun heading(level: Int): TextPaint = body(
    size = when (level) {
        1 -> 21f
        2 -> 18f
        3 -> 15f
        else -> 13f
    },
    bold = true
)

internal fun body(
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

internal fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    style = Paint.Style.FILL
}

internal fun stroke(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    style = Paint.Style.STROKE
    strokeWidth = 1f
}

internal class Pager(private val document: PdfDocument) {

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
