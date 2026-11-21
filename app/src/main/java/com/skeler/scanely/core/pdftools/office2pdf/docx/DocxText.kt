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
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.TabStopSpan
import androidx.core.graphics.withTranslation
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One laid-out paragraph (or one slice of a very long one). Canvas units are PDF points, so
 * every size here is a point size. [draw] can draw any run of lines, which is what lets a
 * paragraph continue on the next page mid-way.
 *
 * [layout] only breaks the text into lines and draws them. Where the lines go is Word's rule, not
 * Android's: [tops] are each line's top (and the last one's bottom) in fractional points from the
 * paragraph's top, [baselines] each line's baseline below its own top ([LineBoxes]).
 */
internal class ParagraphPlan(
    private val layout: StaticLayout,
    private val offset: Float,
    val spaceBefore: Float,
    val spaceAfter: Float,
    private val label: LabelPlan?,
    private val tops: FloatArray,
    private val baselines: FloatArray,
    /** Line [Int] of [layout] laid out alone, with the same width, indent, direction and justification. */
    private val alone: (Int) -> StaticLayout,
    /** The footnotes referred to on each line: (line, note id, number shown). */
    val notes: List<NoteOnLine> = emptyList()
) {
    /** The footnotes referred to on [line]. */
    fun notesOn(line: Int): List<NoteOnLine> = if (notes.isEmpty()) emptyList() else notes.filter { it.line == line }
    val lineCount: Int get() = layout.lineCount

    val textHeight: Float get() = tops[lineCount]

    fun lineHeight(line: Int): Float = tops[line + 1] - tops[line]

    /** The height of lines [start] until [end]. */
    fun height(start: Int, end: Int): Float = tops[end] - tops[start]

    /**
     * Lines [start] until [end], with the first of them at [top]. Each line goes at its own baseline,
     * so each is drawn from a layout of that line alone. Selecting one line of the whole layout with a
     * clip is not enough: StaticLayout draws every line the clip touches, which includes the next line
     * (the clip ends on its top), and the PDF then carries the next line's glyphs whose ink reaches up
     * into the clip, invisible but extracted: Arabic hamzas and tanween read back as hundreds of stray
     * one-letter words (text F1 0.99 -> 0.97).
     */
    fun draw(canvas: Canvas, left: Float, top: Float, start: Int = 0, end: Int = lineCount) {
        for (line in start until end) {
            val baseline = top + tops[line] - tops[start] + baselines[line]
            val single = alone(line)
            if (single.lineCount == 1) {
                canvas.withTranslation(left + offset, baseline - single.getLineBaseline(0)) { single.draw(canvas) }
            } else {
                drawClipped(canvas, left, baseline, line)
            }
            if (line == 0 && label != null) canvas.drawText(label.text, left + offset + label.x, baseline, label.paint)
        }
    }

    /** The line out of the whole layout, selected by a clip: only when it does not lay out alone as one line. */
    private fun drawClipped(canvas: Canvas, left: Float, baseline: Float, line: Int) {
        canvas.withTranslation(left + offset, baseline - layout.getLineBaseline(line)) {
            val top = layout.getLineTop(line).toFloat()
            val bottom = layout.getLineTop(line + 1).toFloat()
            canvas.clipRect(-CLIP_SLACK, top, layout.width + CLIP_SLACK, bottom)
            layout.draw(canvas)
        }
    }
}

/**
 * A font's vertical metrics at one size, all positive, in points. Word lays text out on its printer
 * grid of 600 dots per inch: ascent, descent and leading are each a whole number of dots (10 pt
 * Times New Roman single-spaced is 74 + 18 + 4 dots = 11.52 pt, not 11.49).
 */
internal class FontBox(val ascent: Float, val descent: Float, val leading: Float) {
    val height: Float get() = ascent + descent + leading

    companion object {
        /**
         * [paint]'s font at the [nominal] size the document asks for. The glyphs are set at Word's grid size
         * ([WordGrid.fontSize]: 11 pt is 92 dots, 11.04 pt) but the line metrics come from the size as given,
         * each rounded to whole dots: Calibri 11 pt is 87 + 25 + 0 = 112 dots, Word's 20.16 pt at 1.5 lines on
         * ca_dfo_wave_fetch_a (from the grid size it was 113, 20.35 pt); Times 10 pt is 74 + 18 + 4 either way.
         */
        fun of(paint: Paint, nominal: Float = paint.textSize): FontBox {
            val metrics = paint.fontMetrics
            val scale = if (paint.textSize > 0f) nominal / paint.textSize else 1f
            return FontBox(
                ascent = WordGrid.snap(-metrics.ascent * scale),
                descent = WordGrid.snap(metrics.descent * scale),
                leading = WordGrid.snap(metrics.leading * scale)
            )
        }
    }
}

/** Word's layout grid: 600 dots per inch. Sizes it works with are whole dots. */
internal object WordGrid {
    private const val DOTS_PER_POINT = 600f / 72f

    /** [points] as the nearest whole number of dots, in points. */
    fun snap(points: Float): Float = (points * DOTS_PER_POINT).roundToInt() / DOTS_PER_POINT

    /** A font size as Word sets it: 10 pt is 83 dots, which is 9.96 pt (the size its PDFs report). */
    fun fontSize(points: Float): Float = snap(points).coerceAtLeast(1f / DOTS_PER_POINT)
}

/**
 * Word's line heights. A line's natural height is that of the tallest font on it: ascent, descent and
 * external leading as the font file gives them (Microsoft's "Recommendations for OpenType Fonts",
 * baseline to baseline distance; Android reads the same hhea numbers the metric twins copy from
 * Times New Roman, Arial and Calibri). Measured on Word's own PDFs of the oracle pairs: 10 pt Times
 * New Roman single-spaced is 11.5 pt, Calibri 12 pt is 14.6 pt, and
 * - "exact" is the stated height whatever the font;
 * - "at least" is the stated height unless the natural one is taller (10 pt Times at 232 twips: 11.6 pt);
 * - "auto" multiplies the natural height (240 = single).
 * Where the baseline sits, measured on the same PDFs: in an "auto" line the font's leading and ascent
 * below the line's top, so a multiple's extra height is under the text; in an "exact" or "at least"
 * line the text (ascent and descent) is centred in the line, as ISO/IEC 29500-1 says under the compatibility
 * setting suppressTopSpacing ("all lines in the paragraph are centered within 25 points of spacing").
 * Measured on un_en_c5_79_9 (exact 13.5 pt over 12 pt bold, two exact 6 pt lines over 5 pt, exact 12 pt
 * over 10 pt): baseline steps 6.1 and 1.3 pt in Word's PDF; centred gives 6.1 and 1.4, "descent and
 * leading above the bottom" gave 5.7 and 1.0.
 */
internal object LineBoxes {
    private const val SINGLE_LINE = 240f
    private const val TWIPS = 20f

    /** [tops] (one more than the lines) and [baselines] for [layout] set in [style]; [base] sizes a line with no text. */
    class Boxes(val tops: FloatArray, val baselines: FloatArray)

    fun of(layout: StaticLayout, style: ParagraphStyle, base: FontBox): Boxes {
        val count = layout.lineCount
        val tops = FloatArray(count + 1)
        val baselines = FloatArray(count)
        val text = layout.text as? Spanned
        for (line in 0 until count) {
            val font = tallest(text, layout.getLineStart(line), layout.getLineEnd(line)) ?: base
            // A picture on the line makes it as tall as the layout made it.
            val drawn = (layout.getLineBottom(line) - layout.getLineTop(line)).toFloat()
            val natural = if (hasObject(text, layout.getLineStart(line), layout.getLineEnd(line))) {
                maxOf(font.height, drawn)
            } else {
                font.height
            }
            val height = height(style.lineRule, style.line, natural)
            tops[line + 1] = tops[line] + height
            baselines[line] = baseline(style.lineRule, height, font)
        }
        return Boxes(tops, baselines)
    }

    /** A line's height under [rule] with [line] (twips, or 240ths of single spacing for auto) and its [natural] height. */
    fun height(rule: LineRule, line: Int, natural: Float): Float = when (rule) {
        LineRule.EXACT -> line / TWIPS
        LineRule.AT_LEAST -> maxOf(line / TWIPS, natural)
        LineRule.AUTO -> natural * (line / SINGLE_LINE)
    }.coerceAtLeast(MIN_LINE)

    /** The baseline below the top of a line [height] points tall set in [font]. */
    fun baseline(rule: LineRule, height: Float, font: FontBox): Float = if (rule == LineRule.AUTO) {
        font.leading + font.ascent
    } else {
        (height - font.ascent - font.descent) / 2 + font.ascent
    }

    private fun tallest(text: Spanned?, start: Int, end: Int): FontBox? =
        text?.getSpans(start, end, RunSpan::class.java)?.maxByOrNull { it.box.height }?.box

    private fun hasObject(text: Spanned?, start: Int, end: Int): Boolean =
        text != null && text.getSpans(start, end, PictureSpan::class.java).isNotEmpty()

    private const val MIN_LINE = 1f
}

/** A tab of a first line: the character at [index] stands for a gap [width] points wide. */
internal class TabGap(val index: Int, val width: Float)

/** A tab stop in points from the page margin. */
internal class StopAt(val pos: Float, val kind: TabKind)

/** An empty stretch of a line, as wide as a tab came out. */
internal class GapSpan(private val width: Float) : android.text.style.ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
        width.roundToInt()

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) = Unit
}

/**
 * Where a paragraph's tab characters go: to the next tab stop right of them. Text after a start stop
 * starts at it; text after an end stop ends at it (a right-aligned bullet, a page number at the right
 * margin) and after a centre stop is centred on it, so those depend on the width of what follows.
 */
internal object TabPlan {
    private const val DEFAULT_TAB = 36f
    private const val TWIPS = 20f
    private const val MAX_STOPS = 80
    private const val MAX_SCAN = 600

    /**
     * The paragraph's stops, ascending: its own [tabs], [hanging] (the indent of the lines after the
     * first when the first line starts left of it, which Word treats as a stop), then Word's default
     * stops every half inch past the last of those, across [width].
     */
    fun stops(tabs: List<TabStop>, hanging: Float?, width: Float): List<StopAt> {
        val own = tabs.map { StopAt(it.pos / TWIPS, it.kind) }
        val custom = own + listOfNotNull(hanging?.let { StopAt(it, TabKind.START) })
        val out = custom.sortedBy { it.pos }.toMutableList()
        var next = (((out.lastOrNull()?.pos ?: 0f) / DEFAULT_TAB).toInt() + 1) * DEFAULT_TAB
        while (next < width + DEFAULT_TAB && out.size < MAX_STOPS) {
            out += StopAt(next, TabKind.START)
            next += DEFAULT_TAB
        }
        return out
    }

    /**
     * The tabs of the first line, which starts [start] points from the margin, up to the first that
     * would pass [end], the line's right edge (from there the text has wrapped and Android places it).
     */
    fun firstLine(text: CharSequence, paint: TextPaint, stops: List<StopAt>, start: Float, end: Float): List<TabGap> {
        val out = ArrayList<TabGap>()
        val limit = minOf(text.length, MAX_SCAN)
        var x = start
        var from = 0
        var tab = nextTab(text, from, limit)
        while (tab != null) {
            x += Layout.getDesiredWidth(text, from, tab, paint)
            val stop = stops.firstOrNull { it.pos > x + SAME_PLACE }?.takeIf { x < end } ?: break
            val next = breakAfter(text, tab + 1, limit) ?: limit
            val target = target(stop, x, Layout.getDesiredWidth(text, tab + 1, next, paint))
            out += TabGap(tab, target - x)
            x = target
            from = tab + 1
            tab = nextTab(text, from, limit)
        }
        return out
    }

    /** Where text after a tab at [x] goes for [stop], when what follows it is [following] points wide. */
    private fun target(stop: StopAt, x: Float, following: Float): Float = when (stop.kind) {
        TabKind.END -> maxOf(x, stop.pos - following)
        TabKind.CENTER -> maxOf(x, stop.pos - following / 2)
        else -> stop.pos
    }

    /** The first tab or line break in [from] until [limit]. */
    private fun breakAfter(text: CharSequence, from: Int, limit: Int): Int? =
        (from until limit).firstOrNull { text[it] == '\t' || text[it] == '\n' }

    /** The next tab on the first line: null once a line break or the end comes first. */
    private fun nextTab(text: CharSequence, from: Int, limit: Int): Int? =
        breakAfter(text, from, limit)?.takeIf { text[it] == '\t' }

    private const val SAME_PLACE = 0.05f
}

internal class LabelPlan(val text: String, val paint: TextPaint, val x: Float)

/**
 * Turns a [DocxParagraph] into [ParagraphPlan]s for a given width: indents become per-line
 * indents, runs become spans, pictures become [PictureSpan]s scaled to fit the column and the
 * page. Paragraphs past [CHUNK_CHARS] are split, since one StaticLayout over megabytes of text
 * would stall for a long time with no chance to cancel.
 */
internal class ParagraphFactory(
    private val images: DocxImages,
    private val fonts: DocxFonts,
    private val notes: DocxNotes
) {
    /** The document justifies as Word 2013 and later do ([DocxDocument.shrinkSpaces]); see [narrowSpaces]. */
    var shrinkSpaces = false

    private val arabic = JoinedRuns(fonts, ::paint)

    fun build(paragraph: DocxParagraph, width: Float, maxImageHeight: Float): List<ParagraphPlan> {
        val style = paragraph.style
        val geometry = Geometry(style, width)
        val basePaint = paint(style.mark)
        val label = paragraph.label?.let { geometry.label(it, paint(it.style)) }
        val natural = spannedText(paragraph, geometry.layoutWidth, maxImageHeight)
        if (natural.contains('\t')) placeTabs(natural, style, geometry, basePaint, width)
        val text = forBreaking(natural, shrinkSpaces && style.alignment == ParagraphAlign.JUSTIFY)
        val base = FontBox.of(basePaint, style.mark.halfPoints / 2f)
        val ranges = chunks(text)
        return ranges.mapIndexed { index, range ->
            val first = index == 0
            val slice = if (ranges.size == 1) text else text.subSequence(range.first, range.last + 1)
            val naturalSlice = if (ranges.size == 1) natural else natural.subSequence(range.first, range.last + 1)
            val layout = staticLayout(slice, basePaint, geometry, style.alignment, first)
            val boxes = LineBoxes.of(layout, style, base)
            ParagraphPlan(
                layout = layout,
                offset = geometry.base,
                spaceBefore = if (first) style.spaceBefore / TWIPS_PER_POINT else 0f,
                spaceAfter = if (index == ranges.lastIndex) style.spaceAfter / TWIPS_PER_POINT else 0f,
                label = label.takeIf { first },
                tops = boxes.tops,
                baselines = boxes.baselines,
                alone = { line ->
                    val own = if (text === natural) null else naturalSlice
                    lineLayout(layout, line, basePaint, geometry, style.alignment, first, own)
                },
                notes = notesOf(slice, layout)
            )
        }
    }

    /** The reference's number, with an anchor the layout finds its line by. */
    private fun appendNoteRef(text: SpannableStringBuilder, ref: DocxNoteRef) {
        val start = text.length
        appendText(text, DocxText(ref.mark, ref.style))
        // A custom mark (no number) still anchors its note, as an empty span where the reference is.
        val flags = if (text.length > start) Spanned.SPAN_EXCLUSIVE_EXCLUSIVE else Spanned.SPAN_MARK_MARK
        text.setSpan(NoteAnchor(ref.id, ref.mark), start, text.length, flags)
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

        /** A list label in [paint]: the first line's text starts after it, at the hanging indent if it fits. */
        fun label(listLabel: ListLabel, paint: TextPaint): LabelPlan {
            val labelEnd = firstMargin + paint.measureText(listLabel.text) + LABEL_GAP
            firstMargin = if (labelEnd <= restMargin) restMargin else labelEnd
            return LabelPlan(listLabel.text, paint, labelX)
        }
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
                // A page number the layout did not fill in shows what Word showed last.
                is DocxPageField -> appendText(text, DocxText(inline.cached, inline.style))
                is DocxNoteRef -> appendNoteRef(text, inline)
                // A note's own number the layout did not fill in ([withNoteMark]) shows nothing.
                is DocxNoteMark -> Unit
                is DocxSeparator -> appendSeparator(text)
                // A floating picture takes no room in the text; the page layout draws it at its place ([FloatingPictures]).
                is DocxImage -> if (inline.anchor == null) appendImage(text, inline, width, maxImageHeight)
            }
        }
        return text
    }

    private fun appendText(text: SpannableStringBuilder, run: DocxText) {
        val start = text.length
        text.append(run.text)
        if (text.length == start) return
        // Letters of a right-to-left script join: spacing between them breaks the word. Word sets such text
        // in the run's complex-script font, size and weight (w:cs, w:szCs, w:bCs), not its Latin ones.
        val joined = run.text.any { it in RTL_LETTERS }
        val style = if (joined) run.style.complexScript() else run.style
        text.setSpan(
            runSpan(style, run.text, joined),
            start,
            text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        if (joined) arabic.spaceWidths(text, start, style)
        val background = style.background
        if (background != null) {
            text.setSpan(BackgroundColorSpan(opaque(background)), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (run.text.isNotBlank() && fonts.isReplaced(style.font)) notes.fontsReplaced = true
    }

    /** Super- and subscript are drawn smaller and shifted, as Word does without a dedicated font. */
    private fun runSpan(style: RunStyle, text: String, joined: Boolean = false): RunSpan {
        val size = WordGrid.fontSize(style.halfPoints / 2f)
        val shift = when (style.verticalAlign) {
            VerticalAlign.SUPER -> -size * SUPER_RISE
            VerticalAlign.SUB -> size * SUB_DROP
            VerticalAlign.BASELINE -> 0f
        }
        return RunSpan(
            size = if (style.verticalAlign == VerticalAlign.BASELINE) size else size * SCRIPT_SCALE,
            typeface = fonts.typeface(style.font, style.bold, style.italic),
            // A raised or lowered run never makes its line taller: the line is sized by the full-size font.
            box = FontBox.of(paint(style), style.halfPoints / 2f),
            look = if (joined) arabic.look(style, text) else GlyphLook.of(style, text, paint(style.copy(spacing = 0))),
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

    /**
     * Stops are measured from the margin; the layout measures them from where the first line starts.
     * The first line's tabs become gaps of the width their stops give them. Left to Android as tab
     * characters, a first line that began "2.<tab>" broke a word earlier than the same text does.
     */
    private fun placeTabs(
        text: SpannableStringBuilder,
        style: ParagraphStyle,
        geometry: Geometry,
        paint: TextPaint,
        width: Float
    ) {
        val start = geometry.base + geometry.firstMargin
        val hanging = (geometry.base + geometry.restMargin).takeIf { it > start }
        val stops = TabPlan.stops(style.tabs, hanging, width)
        val gaps = TabPlan.firstLine(text, paint, stops, start, geometry.base + geometry.layoutWidth)
        gaps.asReversed().forEach { gap ->
            text.replace(gap.index, gap.index + 1, OBJECT_CHAR)
            text.setSpan(GapSpan(gap.width), gap.index, gap.index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        stops.filter { it.kind == TabKind.START && it.pos > start }.forEach {
            text.setSpan(TabStopSpan.Standard((it.pos - start).roundToInt()), 0, text.length, PARAGRAPH_SPAN)
        }
    }

    private fun staticLayout(
        text: CharSequence,
        paint: TextPaint,
        geometry: Geometry,
        alignment: ParagraphAlign,
        first: Boolean
    ): StaticLayout {
        val builder = builder(text, 0, text.length, paint, geometry, alignment)
            .setIndents(intArrayOf(firstIndent(geometry, first).toInt(), geometry.restMargin.toInt()), null)
        return builder.build()
    }

    /**
     * Line [line] of [whole] laid out alone: the same text range, width, indent, alignment and
     * paragraph direction. The layout keeps the whole text, so a line that is not the paragraph's
     * last is still justified ([Layout] justifies every line that does not end the text or a paragraph).
     */
    private fun lineLayout(
        whole: StaticLayout,
        line: Int,
        paint: TextPaint,
        geometry: Geometry,
        alignment: ParagraphAlign,
        first: Boolean,
        natural: CharSequence? = null
    ): StaticLayout {
        val indent = if (line == 0) firstIndent(geometry, first) else geometry.restMargin
        val direction = if (whole.getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT) {
            TextDirectionHeuristics.RTL
        } else {
            TextDirectionHeuristics.LTR
        }
        val start = whole.getLineStart(line)
        val end = whole.getLineEnd(line)
        // A line that is not justified (a paragraph's last, or one ending in a break) shows its spaces at their
        // own width when it fits that way; a justified one is stretched to the margin whatever they measure.
        val unjustified = end == whole.text.length || whole.text[end - 1] == '\n'
        val source = natural?.takeIf {
            unjustified && Layout.getDesiredWidth(it, start, end, paint) <= geometry.layoutWidth - indent
        } ?: whole.text
        return builder(source, start, end, paint, geometry, alignment)
            .setIndents(intArrayOf(indent.toInt()), null)
            .setTextDirection(direction)
            .build()
    }

    private fun firstIndent(geometry: Geometry, first: Boolean): Float =
        (if (first) geometry.firstMargin else geometry.restMargin).coerceAtMost(geometry.layoutWidth - MIN_TEXT_WIDTH)

    private fun builder(
        text: CharSequence,
        start: Int,
        end: Int,
        paint: TextPaint,
        geometry: Geometry,
        alignment: ParagraphAlign
    ): StaticLayout.Builder {
        val builder = StaticLayout.Builder.obtain(text, start, end, paint, geometry.layoutWidth.toInt())
            .setAlignment(ALIGNMENTS.getValue(alignment))
            .setIncludePad(false)
        if (alignment == ParagraphAlign.JUSTIFY) justify(builder)
        return builder
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

    private fun paint(style: RunStyle): TextPaint = TextPaint(TEXT_FLAGS).apply {
        textSize = WordGrid.fontSize(style.halfPoints / 2f)
        typeface = fonts.typeface(style.font, style.bold, style.italic)
        GlyphLook.of(style).applyTo(this)
        color = opaque(style.color ?: Color.BLACK)
    }

    private companion object {
        // Linear text: advances stay fractional. Without it Android rounds every glyph's advance to a whole
        // unit, and a unit here is a point: a 10 pt "c" was 4.0 wide instead of 4.44, so lines broke
        // where Word's did not.
        const val TEXT_FLAGS = Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG
        const val MIN_TEXT_WIDTH = 12f
        const val LABEL_GAP = 4f

        /** Hebrew, Arabic, Syriac, Thaana and the Arabic presentation forms. */
        val RTL_LETTERS = '\u0590'..'\u08FF'

        const val SINGLE_LINE = 240f
        const val SCRIPT_SCALE = 0.65f
        const val SUPER_RISE = 0.35f
        const val SUB_DROP = 0.15f
        const val PX_TO_POINTS = 0.75f
        const val PARAGRAPH_SPAN = Spanned.SPAN_INCLUSIVE_INCLUSIVE
        val ALIGNMENTS = mapOf(
            ParagraphAlign.START to Layout.Alignment.ALIGN_NORMAL,
            ParagraphAlign.JUSTIFY to Layout.Alignment.ALIGN_NORMAL,
            ParagraphAlign.CENTER to Layout.Alignment.ALIGN_CENTER,
            ParagraphAlign.END to Layout.Alignment.ALIGN_OPPOSITE
        )
    }
}

/**
 * How a run's glyphs are set besides font and size. Word sets text without standard ligatures (a
 * ligature glyph, "ft" in Carlito, reaches the PDF with no character behind it, so the word could no
 * longer be found or copied) and without pair kerning unless the run asks for it (w:kern); Android
 * does both unless told. [scaleX] is w:w, and carries w:spacing as well.
 *
 * Character spacing is not given to the paint as letter spacing: on a justified line Android (15 and
 * later) replaces a paint's letter spacing with its own while drawing, so the spacing was measured
 * but not drawn and every justified line ended short of the margin by the sum of it. Instead the
 * run's glyphs are widened by the share that makes the run exactly as wide as the spacing would.
 */
internal class GlyphLook(private val features: String, private val scaleX: Float, val linear: Boolean = true) {
    fun applyTo(paint: Paint) {
        paint.isLinearText = linear
        paint.fontFeatureSettings = features
        paint.textScaleX = scaleX
    }

    companion object {
        private const val NO_LIGATURES = "\"liga\" 0, \"clig\" 0"
        private const val NO_KERNING = ", \"kern\" 0"
        private const val PERCENT = 100f
        private const val TWIPS = 20f
        private const val MIN_SCALE = 0.1f
        private const val MAX_SCALE = 6f

        /**
         * Text of a script whose letters join (Arabic, Hebrew): no scale or spacing, which would break the
         * joins, and set as the platform sets it. Its positions are not Word's either way (the fonts differ),
         * and set with fractional advances pdfium read fewer of its words back whole (text F1 0.99 -> 0.97).
         */
        val JOINED = GlyphLook(NO_LIGATURES, 1f, linear = false)

        /** [JOINED] scaled horizontally by [scaleX] as a whole, which keeps the letters joined. */
        fun joined(scaleX: Float): GlyphLook =
            GlyphLook(NO_LIGATURES, scaleX.coerceIn(MIN_SCALE, MAX_SCALE), linear = false)

        /** [style]'s look with no character spacing: for a paint that measures or draws no run of text. */
        fun of(style: RunStyle): GlyphLook = GlyphLook(features(style), style.scale / PERCENT)

        /** The look of [text] set in [style]; [unspaced] is a paint of that style without its character spacing. */
        fun of(style: RunStyle, text: String, unspaced: Paint): GlyphLook {
            val scale = style.scale / PERCENT
            val natural = if (style.spacing == 0 || text.isEmpty()) 0f else unspaced.measureText(text)
            if (natural <= 0f) return GlyphLook(features(style), scale)
            val added = style.spacing / TWIPS * text.codePointCount(0, text.length)
            return GlyphLook(features(style), (scale * (1f + added / natural)).coerceIn(MIN_SCALE, MAX_SCALE))
        }

        private fun features(style: RunStyle) = if (style.kerning) NO_LIGATURES else NO_LIGATURES + NO_KERNING
    }
}

/** All of a run's character formatting in one span, so a size like 10.5 pt stays fractional. */
internal class RunSpan(
    private val size: Float,
    private val typeface: Typeface,
    /** The run's font at its full size: what sizes the line it is on ([LineBoxes]). */
    val box: FontBox,
    private val look: GlyphLook,
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
        look.applyTo(paint)
        paint.baselineShift += shift
    }

    /** A run of a script whose letters join ([GlyphLook.JOINED]). */
    val joined: Boolean get() = !look.linear
}

/** A footnote referred to on [line] of a paragraph: its id and the number shown. */
internal class NoteOnLine(val line: Int, val id: String, val mark: String)

/** Marks a footnote reference in the text so its line can be found. */
internal class NoteAnchor(val id: String, val mark: String)

/** A thin horizontal rule [width] long, drawn where the text's middle is (the footnote separator). */
internal class RuleSpan(private val width: Float) : android.text.style.ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
        width.roundToInt()

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = paint.color
            strokeWidth = RULE_STROKE
        }
        val middle = y - paint.textSize * RULE_RISE
        canvas.drawLine(x, middle, x + width, middle, stroke)
    }

    private companion object {
        const val RULE_STROKE = 0.5f
        const val RULE_RISE = 0.3f
    }
}

/** A space at [scaleX] of its font's own width, whatever its run's look ([JoinedRuns.spaceWidths]). */
internal class SpaceScale(private val scaleX: Float) : MetricAffectingSpan() {
    override fun updateMeasureState(paint: TextPaint) {
        paint.textScaleX = scaleX
    }

    override fun updateDrawState(paint: TextPaint) {
        paint.textScaleX = scaleX
    }
}

/** A space drawn [scale] as wide as its run's: applied after the run's own span, so it scales the run's look. */
internal class NarrowSpace(private val scale: Float) : MetricAffectingSpan() {
    override fun updateMeasureState(paint: TextPaint) {
        paint.textScaleX *= scale
    }

    override fun updateDrawState(paint: TextPaint) {
        paint.textScaleX *= scale
    }
}

private fun opaque(rgb: Int): Int = rgb or OPAQUE

private const val OPAQUE = -0x1000000
private const val UNDERLINE = 1
private const val STRIKE = 2
private const val CLIP_SLACK = 10_000f
