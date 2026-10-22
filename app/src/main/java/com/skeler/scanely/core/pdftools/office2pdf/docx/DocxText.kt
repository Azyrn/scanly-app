package com.skeler.scanely.core.pdftools.office2pdf.docx

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.TabStopSpan
import androidx.core.graphics.withTranslation
import kotlin.math.max
import kotlin.math.min

/**
 * One laid-out paragraph (or one slice of a very long one). Canvas units are PDF points, so
 * every size here is a point size. [draw] can draw any run of lines, which is what lets a
 * paragraph continue on the next page mid-way.
 */
internal class ParagraphPlan(
    val layout: StaticLayout,
    private val offset: Float,
    val spaceBefore: Float,
    val spaceAfter: Float,
    private val label: LabelPlan?
) {
    val lineCount: Int get() = layout.lineCount

    val textHeight: Float get() = layout.height.toFloat()

    fun lineHeight(line: Int): Float = (layout.getLineTop(line + 1) - layout.getLineTop(line)).toFloat()

    /** Lines [start] until [end], with the first of them at [top]. */
    fun draw(canvas: Canvas, left: Float, top: Float, start: Int = 0, end: Int = lineCount) {
        val sliceTop = layout.getLineTop(start).toFloat()
        val sliceBottom = layout.getLineTop(end).toFloat()
        canvas.withTranslation(left + offset, top - sliceTop) {
            // StaticLayout draws every line the clip touches, so the clip is what selects the slice.
            canvas.clipRect(-CLIP_SLACK, sliceTop, layout.width + CLIP_SLACK, sliceBottom)
            layout.draw(canvas)
            if (start == 0 && label != null) {
                canvas.drawText(label.text, label.x, layout.getLineBaseline(0).toFloat(), label.paint)
            }
        }
    }
}

internal class LabelPlan(val text: String, val paint: TextPaint, val x: Float)

/**
 * Turns a [DocxParagraph] into [ParagraphPlan]s for a given width: indents become per-line
 * indents, runs become spans, pictures become [PictureSpan]s scaled to fit the column and the
 * page. Paragraphs past [CHUNK_CHARS] are split, since one StaticLayout over megabytes of text
 * would stall for a long time with no chance to cancel.
 */
internal class ParagraphFactory(private val images: DocxImages, private val notes: DocxNotes) {

    fun build(paragraph: DocxParagraph, width: Float, maxImageHeight: Float): List<ParagraphPlan> {
        val style = paragraph.style
        val geometry = Geometry(style, width)
        val basePaint = paint(style.mark)
        val label = paragraph.label?.let { listLabel ->
            val labelPaint = paint(listLabel.style)
            val labelEnd = geometry.firstMargin + labelPaint.measureText(listLabel.text) + LABEL_GAP
            geometry.firstMargin = if (labelEnd <= geometry.restMargin) geometry.restMargin else labelEnd
            LabelPlan(listLabel.text, labelPaint, geometry.labelX)
        }
        val text = spannedText(paragraph, geometry.layoutWidth, maxImageHeight)
        val multiplier = lineMultiplier(style, paragraph)
        val ranges = chunks(text)
        return ranges.mapIndexed { index, range ->
            val first = index == 0
            val slice = if (ranges.size == 1) text else text.subSequence(range.first, range.last + 1)
            ParagraphPlan(
                layout = staticLayout(slice, basePaint, geometry, style.alignment, multiplier, first),
                offset = geometry.base,
                spaceBefore = if (first) style.spaceBefore / TWIPS_PER_POINT else 0f,
                spaceAfter = if (index == ranges.lastIndex) style.spaceAfter / TWIPS_PER_POINT else 0f,
                label = label.takeIf { first }
            )
        }
    }

    /**
     * Word measures indents from the margin and lets a hanging first line start left of the
     * rest; the layout gets one left edge ([base]) and per-line indents from it. Negative
     * indents (into the margin) are clamped to the margin.
     */
    private class Geometry(style: ParagraphStyle, width: Float) {
        private val left = (style.indentLeft / TWIPS_PER_POINT).coerceAtLeast(0f)
        private val right = (style.indentRight / TWIPS_PER_POINT).coerceAtLeast(0f)
        private val firstStart = (left + style.firstLine / TWIPS_PER_POINT).coerceAtLeast(0f)
        val base = min(left, firstStart).coerceAtMost(max(0f, width - MIN_TEXT_WIDTH))
        val layoutWidth = (width - base - right).coerceAtLeast(MIN_TEXT_WIDTH)
        val labelX = firstStart - base
        val restMargin = (left - base).coerceAtMost(layoutWidth - MIN_TEXT_WIDTH).coerceAtLeast(0f)
        var firstMargin = labelX
    }

    private fun spannedText(
        paragraph: DocxParagraph,
        width: Float,
        maxImageHeight: Float
    ): SpannableStringBuilder {
        val text = SpannableStringBuilder()
        for (inline in paragraph.inlines) {
            when (inline) {
                is DocxText -> appendText(text, inline)
                is DocxImage -> appendImage(text, inline, width, maxImageHeight)
            }
        }
        if (text.contains('\t')) {
            val stops = (width / DEFAULT_TAB).toInt().coerceIn(1, MAX_TAB_STOPS)
            for (stop in 1..stops) {
                text.setSpan(TabStopSpan.Standard((stop * DEFAULT_TAB).toInt()), 0, text.length, PARAGRAPH_SPAN)
            }
        }
        return text
    }

    private fun appendText(text: SpannableStringBuilder, run: DocxText) {
        val style = run.style
        val start = text.length
        text.append(run.text)
        if (text.length == start) return
        text.setSpan(runSpan(style), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val background = style.background
        if (background != null) {
            text.setSpan(BackgroundColorSpan(opaque(background)), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (run.text.isNotBlank() && DocxFonts.isReplaced(style.font)) notes.fontsReplaced = true
    }

    /** Super- and subscript are drawn smaller and shifted, as Word does without a dedicated font. */
    private fun runSpan(style: RunStyle): RunSpan {
        val size = style.halfPoints / 2f
        val shift = when (style.verticalAlign) {
            VerticalAlign.SUPER -> -size * SUPER_RISE
            VerticalAlign.SUB -> size * SUB_DROP
            VerticalAlign.BASELINE -> 0f
        }
        return RunSpan(
            size = if (style.verticalAlign == VerticalAlign.BASELINE) size else size * SCRIPT_SCALE,
            typeface = DocxFonts.typeface(style.font, style.bold, style.italic),
            color = opaque(style.color ?: Color.BLACK),
            decoration = (if (style.underline) UNDERLINE else 0) or (if (style.strike) STRIKE else 0),
            shift = shift.toInt()
        )
    }

    /** Extents of zero (some VML) fall back to the picture's own size at 96 dpi. */
    private fun appendImage(
        text: SpannableStringBuilder,
        image: DocxImage,
        maxWidth: Float,
        maxHeight: Float
    ) {
        val pixels = images.size(image.entry)
        if (pixels == null) {
            notes.unreadableImages++
            return
        }
        val declared = image.widthEmu > 0 && image.heightEmu > 0
        val width = if (declared) image.widthEmu / EMU_PER_POINT else pixels.width * PX_TO_POINTS
        val height = if (declared) image.heightEmu / EMU_PER_POINT else pixels.height * PX_TO_POINTS
        val scale = minOf(1f, maxWidth / width, maxHeight / height)
        val start = text.length
        text.append(OBJECT_CHAR)
        text.setSpan(PictureSpan(images, image.entry, width * scale, height * scale), start, text.length, 0)
    }

    private fun staticLayout(
        text: CharSequence,
        paint: TextPaint,
        geometry: Geometry,
        alignment: ParagraphAlign,
        multiplier: Float,
        first: Boolean
    ): StaticLayout {
        val firstIndent = (if (first) geometry.firstMargin else geometry.restMargin)
            .coerceAtMost(geometry.layoutWidth - MIN_TEXT_WIDTH)
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, geometry.layoutWidth.toInt())
            .setAlignment(ALIGNMENTS.getValue(alignment))
            .setIncludePad(false)
            .setLineSpacing(0f, multiplier)
            .setIndents(intArrayOf(firstIndent.toInt(), geometry.restMargin.toInt()), null)
        if (alignment == ParagraphAlign.JUSTIFY) justify(builder)
        return builder.build()
    }

    /**
     * The builder takes LineBreaker's constant from API 29; on 26-28 the same value lives on
     * Layout, which lint can't see is equal, hence the suppression on that branch only.
     */
    @SuppressLint("WrongConstant")
    private fun justify(builder: StaticLayout.Builder) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                builder.setJustificationMode(LineBreaker.JUSTIFICATION_MODE_INTER_WORD)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        }
    }

    /**
     * "Auto" spacing is a multiple of single spacing; exact and at-least spacing are point
     * heights, turned into a multiple of the paragraph's largest font's natural line.
     */
    private fun lineMultiplier(style: ParagraphStyle, paragraph: DocxParagraph): Float {
        if (style.lineRule == LineRule.AUTO) {
            return (style.line / SINGLE_LINE).coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
        }
        val largest = paragraph.inlines.filterIsInstance<DocxText>().maxOfOrNull { it.style.halfPoints }
            ?: style.mark.halfPoints
        val metrics = TextPaint().apply { textSize = largest / 2f }.fontMetrics
        val natural = (metrics.descent - metrics.ascent).coerceAtLeast(1f)
        val wanted = (style.line / TWIPS_PER_POINT / natural).coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
        return if (style.lineRule == LineRule.AT_LEAST) max(1f, wanted) else wanted
    }

    private fun paint(style: RunStyle): TextPaint = TextPaint(TEXT_FLAGS).apply {
        textSize = style.halfPoints / 2f
        typeface = DocxFonts.typeface(style.font, style.bold, style.italic)
        color = opaque(style.color ?: Color.BLACK)
    }

    /** Slices of at most [CHUNK_CHARS], cut after a line break or space where one is near. */
    private fun chunks(text: CharSequence): List<IntRange> {
        if (text.length <= CHUNK_CHARS) return listOf(0 until text.length)
        val ranges = ArrayList<IntRange>()
        var start = 0
        while (start < text.length) {
            var end = min(start + CHUNK_CHARS, text.length)
            if (end < text.length) {
                val window = text.subSequence(end - CHUNK_SEARCH, end)
                val cut = max(window.lastIndexOf('\n'), window.lastIndexOf(' '))
                if (cut >= 0) end = end - CHUNK_SEARCH + cut + 1
            }
            ranges += start until end
            start = end
        }
        return ranges
    }

    private companion object {
        const val TEXT_FLAGS = Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG
        const val CHUNK_CHARS = 20_000
        const val CHUNK_SEARCH = 2_000
        const val MIN_TEXT_WIDTH = 12f
        const val LABEL_GAP = 4f
        const val DEFAULT_TAB = 36f
        const val MAX_TAB_STOPS = 40
        const val SINGLE_LINE = 240f
        const val MIN_MULTIPLIER = 0.5f
        const val MAX_MULTIPLIER = 5f
        const val SCRIPT_SCALE = 0.65f
        const val SUPER_RISE = 0.35f
        const val SUB_DROP = 0.15f
        const val PX_TO_POINTS = 0.75f
        const val OBJECT_CHAR = "\uFFFC"
        const val PARAGRAPH_SPAN = Spanned.SPAN_INCLUSIVE_INCLUSIVE
        val ALIGNMENTS = mapOf(
            ParagraphAlign.START to Layout.Alignment.ALIGN_NORMAL,
            ParagraphAlign.JUSTIFY to Layout.Alignment.ALIGN_NORMAL,
            ParagraphAlign.CENTER to Layout.Alignment.ALIGN_CENTER,
            ParagraphAlign.END to Layout.Alignment.ALIGN_OPPOSITE
        )
    }
}

/** All of a run's character formatting in one span, so a size like 10.5 pt stays fractional. */
private class RunSpan(
    private val size: Float,
    private val typeface: Typeface,
    private val color: Int,
    private val decoration: Int,
    private val shift: Int
) : MetricAffectingSpan() {
    override fun updateMeasureState(paint: TextPaint) = applyMetrics(paint)

    override fun updateDrawState(paint: TextPaint) {
        applyMetrics(paint)
        paint.color = color
        paint.isUnderlineText = decoration and UNDERLINE != 0
        paint.isStrikeThruText = decoration and STRIKE != 0
    }

    private fun applyMetrics(paint: TextPaint) {
        paint.textSize = size
        paint.typeface = typeface
        paint.baselineShift += shift
    }
}

private fun opaque(rgb: Int): Int = rgb or OPAQUE

private const val OPAQUE = -0x1000000
private const val UNDERLINE = 1
private const val STRIKE = 2
private const val CLIP_SLACK = 10_000f
