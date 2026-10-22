package com.skeler.scanely.core.pdfeditor.engine

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A run of original text, with everything needed to cover it and redraw it in its own style. */
data class TextSelection(
    val range: IntRange,
    val text: String,
    /** One rectangle per visual line the selection spans. */
    val quads: List<Rect>,
    val bounds: Rect,
    /** The y the original glyphs sit on, so a replacement lands on the same line, not below it. */
    val baselinePt: Float,
    val fontSizePt: Float
)

/**
 * One page's text geometry, lifted out of [PdfTextLayer] in a single pass and held in memory.
 *
 * Text-anchored highlighting answers "which character is under this finger" on every touch sample;
 * reopening the native text layer per sample is what makes that feel laggy. Rectangles are
 * top-left-origin page points, as [PdfTextLayer] delivers them.
 *
 * Glyphless characters — line-break spaces, soft hyphens, pdfium's inter-line controls — arrive as
 * [Rect.Zero]. They keep their index but have no position, so every geometric walk skips them
 * rather than treating them as a glyph at the page corner.
 */
class PageTextSnapshot(
    private val boxes: List<Rect>,
    private val fontSizes: FloatArray,
    private val text: String
) {

    val charCount: Int get() = boxes.size

    val isEmpty: Boolean get() = boxes.isEmpty()

    /** Null when nothing is close enough — the caller falls back to free-form marking. */
    fun charIndexAt(point: Offset, tolerancePt: Float = HIT_TOLERANCE_PT): Int? {
        var best = -1
        var bestDistance = Float.MAX_VALUE
        boxes.forEachIndexed { index, box ->
            if (!box.isGlyph) return@forEachIndexed
            val distance = distanceTo(box, point)
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best.takeIf { it >= 0 && bestDistance <= tolerancePt }
    }

    /** The character range a drag from [from] to [to] covers, in reading order. */
    fun rangeBetween(from: Offset, to: Offset): IntRange? {
        val start = charIndexAt(from, DRAG_TOLERANCE_PT) ?: return null
        val end = charIndexAt(to, DRAG_TOLERANCE_PT) ?: return null
        return min(start, end)..max(start, end)
    }

    fun wordAt(index: Int): IntRange {
        if (index !in text.indices) return IntRange.EMPTY
        var start = index
        var end = index
        while (start > 0 && !text[start - 1].isWhitespace()) start--
        while (end + 1 < text.length && !text[end + 1].isWhitespace()) end++
        return start..end
    }

    /**
     * The visual line containing [index], found by walking out while the vertical centre holds.
     *
     * Positionless characters are stepped over rather than ending the walk. A space between two
     * words is frequently one of them, and stopping there is what used to turn "select the line"
     * into "select one word".
     */
    fun lineAt(index: Int): IntRange {
        if (index !in boxes.indices || !boxes[index].isGlyph) return IntRange.EMPTY
        val anchor = boxes[index]
        val centre = anchor.center.y
        val tolerance = max(anchor.height, MIN_LINE_HEIGHT_PT) / 2f

        var start = index
        var end = index
        var cursor = index
        while (cursor > 0) {
            cursor--
            val box = boxes[cursor]
            if (!box.isGlyph) continue
            if (!sharesLine(box, centre, tolerance)) break
            start = cursor
        }
        cursor = index
        while (cursor + 1 < boxes.size) {
            cursor++
            val box = boxes[cursor]
            if (!box.isGlyph) continue
            if (!sharesLine(box, centre, tolerance)) break
            end = cursor
        }
        return start..end
    }

    /** One rectangle per visual line, which is what a highlight or underline should cover. */
    fun quads(range: IntRange): List<Rect> {
        val clamped = clamp(range)
        if (clamped.isEmpty()) return emptyList()

        val quads = mutableListOf<Rect>()
        var current: Rect? = null
        for (index in clamped) {
            val box = boxes[index]
            if (!box.isGlyph) continue
            val previous = current
            current = when {
                previous == null -> box
                abs(previous.center.y - box.center.y) <= max(previous.height, box.height) / 2f ->
                    previous.union(box)
                else -> {
                    quads += previous
                    box
                }
            }
        }
        current?.let { quads += it }
        return quads
    }

    /**
     * The baseline the glyphs in [range] sit on.
     *
     * Taken as the most common box bottom rather than the lowest, so a comma or a "y" hanging
     * below the line doesn't drag the baseline down with it.
     */
    fun baselineOf(range: IntRange): Float? {
        val clamped = clamp(range)
        if (clamped.isEmpty()) return null
        val bottoms = clamped
            .map { boxes[it] }
            .filter { it.isGlyph }
            .map { it.bottom }
        if (bottoms.isEmpty()) return null
        return bottoms
            .groupingBy { (it / BASELINE_BUCKET_PT).toInt() }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?.let { bucket ->
                // Average within the winning bucket, so the answer isn't quantised to the bucket.
                bottoms.filter { (it / BASELINE_BUCKET_PT).toInt() == bucket }.average().toFloat()
            }
    }

    /**
     * The size replacement text should be drawn at: the most common size in the range rather than
     * the first character's, so a run starting with a drop cap keeps the body size.
     */
    fun dominantFontSize(range: IntRange): Float {
        val clamped = clamp(range)
        if (clamped.isEmpty()) return DEFAULT_FONT_SIZE_PT
        return clamped
            .map { fontSizes[it] }
            .filter { it > 0f }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?: DEFAULT_FONT_SIZE_PT
    }

    fun textIn(range: IntRange): String {
        val clamped = clamp(range)
        return if (clamped.isEmpty()) "" else text.substring(clamped.first, clamped.last + 1)
    }

    /** Everything about [range] a caller needs to cover it and redraw it in the same style. */
    fun selectionOf(range: IntRange): TextSelection? {
        val clamped = clamp(range)
        if (clamped.isEmpty()) return null
        val quads = quads(clamped).takeIf { it.isNotEmpty() } ?: return null
        val bounds = quads.reduce { a, b -> a.union(b) }
        return TextSelection(
            range = clamped,
            text = textIn(clamped),
            quads = quads,
            bounds = bounds,
            baselinePt = baselineOf(clamped) ?: bounds.bottom,
            fontSizePt = dominantFontSize(clamped)
        )
    }

    /** The line under [point], as a full selection. Null where the point isn't on any text. */
    fun lineSelectionAt(point: Offset, tolerancePt: Float = HIT_TOLERANCE_PT): TextSelection? {
        val index = charIndexAt(point, tolerancePt) ?: return null
        return selectionOf(lineAt(index))
    }

    /**
     * The selection a drag from [from] to [to] covers, rounded out to whole words.
     *
     * A fingertip covers several characters at reading size, so ending a drag on the exact letter
     * meant is not something a touch screen can offer. Snapping to word boundaries is what every
     * text selection on a phone does, and without it a drag across "due now" routinely selects
     * "due n" — which then gets replaced, leaving "ow" stranded on the line.
     */
    fun selectionBetween(from: Offset, to: Offset): TextSelection? {
        val range = rangeBetween(from, to) ?: return null
        val start = wordAt(range.first).let { if (it.isEmpty()) range.first else it.first }
        val end = wordAt(range.last).let { if (it.isEmpty()) range.last else it.last }
        return selectionOf(start..end)
    }

    private val Rect.isGlyph: Boolean get() = width > 0f && height > 0f

    private fun sharesLine(box: Rect, centre: Float, tolerance: Float): Boolean =
        abs(box.center.y - centre) <= tolerance

    private fun clamp(range: IntRange): IntRange {
        if (boxes.isEmpty() || range.isEmpty()) return IntRange.EMPTY
        val first = range.first.coerceIn(0, boxes.lastIndex)
        val last = range.last.coerceIn(0, boxes.lastIndex)
        return if (first > last) IntRange.EMPTY else first..last
    }

    private fun distanceTo(box: Rect, point: Offset): Float {
        val dx = max(max(box.left - point.x, point.x - box.right), 0f)
        val dy = max(max(box.top - point.y, point.y - box.bottom), 0f)
        return if (dx == 0f && dy == 0f) 0f else kotlin.math.hypot(dx, dy)
    }

    private fun Rect.union(other: Rect) = Rect(
        min(left, other.left),
        min(top, other.top),
        max(right, other.right),
        max(bottom, other.bottom)
    )

    companion object {
        const val HIT_TOLERANCE_PT = 8f
        const val DRAG_TOLERANCE_PT = 28f
        private const val MIN_LINE_HEIGHT_PT = 6f
        private const val DEFAULT_FONT_SIZE_PT = 11f

        /** Baselines within a point of each other are the same baseline. */
        private const val BASELINE_BUCKET_PT = 1f
    }
}
