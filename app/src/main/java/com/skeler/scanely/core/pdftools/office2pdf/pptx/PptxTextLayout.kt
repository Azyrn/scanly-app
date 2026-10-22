package com.skeler.scanely.core.pdftools.office2pdf.pptx

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import androidx.core.graphics.withTranslation
import kotlin.math.ceil

private const val EXCLUSIVE = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
private const val BULLET_GAP = "\u2002"

/** An empty paragraph still takes a line; a zero-width space gives it something to size. */
private const val EMPTY_LINE = "\u200B"

private const val MIN_TEXT_SIZE = 1f
private const val MIN_LINE_SPACING = 0.5f
private const val MAX_LINE_SPACING = 3f

/** Lays out and draws [TextBody]s with one reusable paint. */
internal class PptxTextLayout {

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG)

    fun textHeight(text: TextBody, width: Float): Float =
        layout(text, width).height + text.insets.top + text.insets.bottom

    /** Draws [text] in a box of [w] × [h] at the canvas origin; text may overflow, as on a slide. */
    fun drawText(canvas: Canvas, text: TextBody, w: Float, h: Float) {
        val layout = layout(text, w)
        val available = h - text.insets.top - text.insets.bottom
        val top = when (text.anchor) {
            Anchor.TOP -> text.insets.top
            Anchor.CENTER -> text.insets.top + (available - layout.height) / 2
            Anchor.BOTTOM -> h - text.insets.bottom - layout.height
        }
        canvas.withTranslation(text.insets.left, top) {
            layout.draw(canvas)
        }
    }

    private fun layout(text: TextBody, boxWidth: Float): StaticLayout {
        val content = spannable(text)
        textPaint.textSize = DEFAULT_TEXT_SIZE * text.fontScale
        textPaint.color = BLACK
        val inner = boxWidth - text.insets.left - text.insets.right
        val width = if (text.wrap) {
            inner
        } else {
            maxOf(inner, Layout.getDesiredWidth(content, textPaint))
        }
        val spacing = (text.paragraphs.firstOrNull()?.lineSpacing ?: 1f) * (1 - text.lineReduction)
        val layoutWidth = ceil(width).toInt().coerceAtLeast(1)
        return StaticLayout.Builder.obtain(content, 0, content.length, textPaint, layoutWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, spacing.coerceIn(MIN_LINE_SPACING, MAX_LINE_SPACING))
            .setIncludePad(false)
            .build()
    }
}

private fun spannable(text: TextBody): SpannableStringBuilder {
    val sb = SpannableStringBuilder()
    text.paragraphs.forEachIndexed { index, p ->
        if (index > 0) sb.append('\n')
        val start = sb.length
        val firstRun = p.runs.firstOrNull { it.text.isNotBlank() }
        if (p.bullet != null && firstRun != null) appendBullet(sb, p.bullet, p.bulletColor, firstRun)
        if (p.runs.isEmpty()) {
            sb.append(EMPTY_LINE)
            sb.setSpan(RelativeSizeSpan(relative(p.endSize)), sb.length - 1, sb.length, EXCLUSIVE)
        }
        for (run in p.runs) appendRun(sb, run)
        styleParagraph(sb, p, start)
    }
    return sb
}

/** The bullet takes the first visible run's size and font, and its colour unless buClr says otherwise. */
private fun appendBullet(sb: SpannableStringBuilder, bullet: String, bulletColor: Int?, firstRun: TextRun) {
    val bulletStart = sb.length
    sb.append(bullet).append(BULLET_GAP)
    sb.setSpan(ForegroundColorSpan(bulletColor ?: firstRun.color), bulletStart, sb.length, EXCLUSIVE)
    sb.setSpan(RelativeSizeSpan(relative(firstRun.size)), bulletStart, sb.length, EXCLUSIVE)
    sb.setSpan(TypefaceSpan(family(firstRun.font)), bulletStart, sb.length, EXCLUSIVE)
}

private fun appendRun(sb: SpannableStringBuilder, run: TextRun) {
    val runStart = sb.length
    sb.append(run.text)
    val end = sb.length
    sb.setSpan(RelativeSizeSpan(relative(run.size)), runStart, end, EXCLUSIVE)
    sb.setSpan(ForegroundColorSpan(run.color), runStart, end, EXCLUSIVE)
    sb.setSpan(TypefaceSpan(family(run.font)), runStart, end, EXCLUSIVE)
    val style = typefaceStyle(run)
    if (style != Typeface.NORMAL) sb.setSpan(StyleSpan(style), runStart, end, EXCLUSIVE)
    if (run.underline) sb.setSpan(UnderlineSpan(), runStart, end, EXCLUSIVE)
    if (run.strike) sb.setSpan(StrikethroughSpan(), runStart, end, EXCLUSIVE)
}

private fun typefaceStyle(run: TextRun): Int = when {
    run.bold && run.italic -> Typeface.BOLD_ITALIC
    run.bold -> Typeface.BOLD
    run.italic -> Typeface.ITALIC
    else -> Typeface.NORMAL
}

/** Alignment and indents for the paragraph that starts at [start]; negative indents are clamped to 0. */
private fun styleParagraph(sb: SpannableStringBuilder, p: Paragraph, start: Int) {
    val end = sb.length
    if (end <= start) return
    sb.setSpan(AlignmentSpan.Standard(alignment(p.align)), start, end, EXCLUSIVE)
    val rest = p.marginLeft.coerceAtLeast(0f)
    val first = (p.marginLeft + p.indent).coerceAtLeast(0f)
    if (rest > 0f || first > 0f) {
        sb.setSpan(LeadingMarginSpan.Standard(first.toInt(), rest.toInt()), start, end, EXCLUSIVE)
    }
}

/** Relative to the paint's size, which already carries the autofit scale, so sizes stay fractional. */
private fun relative(size: Float): Float = size.coerceAtLeast(MIN_TEXT_SIZE) / DEFAULT_TEXT_SIZE

private fun alignment(align: TextAlign): Layout.Alignment = when (align) {
    TextAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL
    TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
    TextAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
}

private fun family(font: FontKind): String = when (font) {
    FontKind.SANS -> "sans-serif"
    FontKind.SERIF -> "serif"
    FontKind.MONO -> "monospace"
}
