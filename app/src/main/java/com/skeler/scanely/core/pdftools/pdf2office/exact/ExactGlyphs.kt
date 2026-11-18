package com.skeler.scanely.core.pdftools.pdf2office.exact

import com.skeler.scanely.core.pdfeditor.engine.PdfiumChar
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** A drawn character, or a break pdfium reports between them. */
internal sealed interface Item

internal data object LineBreak : Item

/** A space, with where it sat along the baseline when the PDF says ([u] is NaN otherwise). */
internal class Space(val u: Float, val v: Float, val generated: Boolean = false) : Item

internal class Glyph(
    var text: String,
    val code: Int,
    val style: TextStyle,
    val angle: Int,
    var u0: Float,
    var u1: Float,
    val v: Float,
    val rtl: Boolean,
    val strongLtr: Boolean,
    /** Extent from the advance box, for when the ink box can't be trusted (see [ExactBidiSegments]). */
    val looseU0: Float,
    val looseU1: Float,
    /** Where the glyph was placed along the baseline (the text origin), not where its ink is. */
    val origin: Float = 0f,
    /** The font could not name this glyph and the PDF's mapping may be wrong ([PdfiumChar.uncertain]). */
    val uncertain: Boolean = false,
    /** The marked-content id the glyph was drawn under ([PdfiumChar.mcid]), or -1. */
    val mcid: Int = -1
) : Item

/** First stage of [ExactLayout]: pdfium's characters as glyphs and spaces along their baselines. */
internal object ExactGlyphs {

    fun glyphs(chars: List<PdfiumChar>): List<Item> {
        val items = ArrayList<Item>(chars.size)
        val recent = ArrayDeque<Glyph>()
        for ((i, c) in chars.withIndex()) {
            val code = codeOf(c)
            val item = when {
                c.isHidden() -> null
                isLineBreak(code) -> LineBreak
                isSpace(c, code) -> spaceOf(c)
                isSkipped(c, code) -> null
                else -> {
                    val right = GlyphExtent.right(c, chars.getOrNull(i + 1))
                    unlessTwin(GlyphGeometry.glyph(c, code, GlyphExtent.left(c), right), recent)
                }
            }
            if (item != null) items += item
        }
        return items
    }

    /** The most common size among [glyphs] (1 when there are none). */
    fun dominantSize(glyphs: List<Glyph>): Float =
        glyphs.groupingBy { it.style.sizePt }.eachCount().maxByOrNull { it.value }?.key ?: 1f

    /** Text inside a form field's appearance, or drawn invisibly (an OCR layer). */
    private fun PdfiumChar.isHidden(): Boolean = inForm || invisible

    private fun isLineBreak(code: Int): Boolean = code == '\r'.code || code == '\n'.code

    private fun codeOf(c: PdfiumChar): Int = when (c.code) {
        // pdfium's markers for a hyphen it drew at a line end.
        LINE_END_HYPHEN, 0x02 -> '-'.code
        else -> c.code
    }

    private fun spaceOf(c: PdfiumChar): Space =
        Space(GlyphGeometry.spaceCentre(c), c.originY, c.generated || c.right - c.left < NO_WIDTH_PT)

    private fun isSpace(c: PdfiumChar, code: Int): Boolean =
        c.generated || code == ' '.code || code == NO_BREAK_SPACE || code == '\t'.code

    /** Control codes, and pdfium's "no character" marks that take no room. */
    private fun isSkipped(c: PdfiumChar, code: Int): Boolean =
        code < ' '.code || code == NONCHARACTER || code == REPLACEMENT_CHARACTER && c.right <= c.left

    /** [glyph], unless it was just painted on the same spot (fake bold, fill + stroke passes): one letter. */
    private fun unlessTwin(glyph: Glyph, recent: ArrayDeque<Glyph>): Glyph? {
        if (recent.any { it.code == glyph.code && GlyphGeometry.overlap(it, glyph) > DUPLICATE_OVERLAP }) return null
        recent.addLast(glyph)
        if (recent.size > DUPLICATE_WINDOW) recent.removeFirst()
        return glyph
    }

    private const val DUPLICATE_OVERLAP = 0.7f
    private const val DUPLICATE_WINDOW = 8
    const val NO_WIDTH_PT = 0.01f
    private const val NO_BREAK_SPACE = 0xA0
    private const val NONCHARACTER = 0xFFFF
    private const val LINE_END_HYPHEN = 0xFFFE
    private const val REPLACEMENT_CHARACTER = 0xFFFD
}

/**
 * A character's advance box along an upright baseline: from its origin to where its advance ends.
 * pdfium's loose box was that; since 2024 it is that box united with the ink box
 * (cpdf_textpage.cpp, GetLooseBounds), so a glyph that overhangs its advance (an Arial "r" by
 * 0.27 pt at 19 pt, an italic "f" on both sides) made words measure wider than they were set.
 * The left edge is the origin again. The right edge is the loose box's wherever that reaches past
 * the ink (it is then the advance); where the ink reaches it, the advance ends at the next
 * character's origin when one follows on the baseline, else the box is kept.
 */
internal object GlyphExtent {
    private const val SAME_PT = 0.005f

    fun left(c: PdfiumChar): Float = if (upright(c) && c.left < c.originX) c.originX else c.left

    fun right(c: PdfiumChar, next: PdfiumChar?): Float {
        val overhangs = upright(c) && c.right <= c.inkRight + SAME_PT
        val follows = next != null && !next.generated && next.originY == c.originY &&
            next.originX > c.originX && next.originX < c.right && !repaint(c, next)
        return if (overhangs && follows) next!!.originX else c.right
    }

    /**
     * [next] is [c] painted again a little to the side (fake bold, fill + stroke passes), not the
     * next letter: ending [c] at its origin would shrink it below the overlap that makes the two
     * one letter ([ExactGlyphs.unlessTwin]).
     */
    private fun repaint(c: PdfiumChar, next: PdfiumChar): Boolean =
        next.code == c.code && next.originX - c.originX < (c.right - c.originX) * REPAINT_SHARE

    /** A repaint is shifted by less than this share of the character's advance. */
    private const val REPAINT_SHARE = 0.3f

    private fun upright(c: PdfiumChar): Boolean = c.matrixB == 0f && c.matrixC == 0f && c.matrixA > 0f
}

/** Where a pdfium character sits along its baseline, and how it looks. */
internal object GlyphGeometry {

    /** [left], [right]: the character's advance box ([GlyphExtent]) when known better than its loose box. */
    fun glyph(c: PdfiumChar, code: Int, left: Float = c.left, right: Float = c.right): Glyph {
        val size = c.effectiveSize.takeIf { it > MIN_SIZE_PT } ?: (c.top - c.bottom).coerceAtLeast(1f)
        val angle = angleOf(c)
        val radians = Math.toRadians(angle.toDouble())
        val dx = cos(radians).toFloat()
        val dy = sin(radians).toFloat()
        // Extent along the baseline from the box corners; baseline offset from the origin.
        // Joined scripts use the drawn extent: the advance boxes pdfium reports for Arabic letters
        // overlap their neighbours, which hides the gaps between words.
        val ink = ExactScripts.isArabic(code) || ExactScripts.isRtl(code)
        val corners = if (ink) {
            corners(c.inkLeft, c.inkBottom, c.inkRight, c.inkTop, dx, dy)
        } else {
            corners(left, c.bottom, right, c.top, dx, dy)
        }
        val loose = corners(left, c.bottom, right, c.top, dx, dy)
        val origin = c.originX * dx + c.originY * dy
        var u0 = corners.min()
        var u1 = corners.max()
        if (u1 - u0 < ExactGlyphs.NO_WIDTH_PT) {
            u0 = origin
            u1 = origin + size * HALF
        }
        val v = -c.originX * dy + c.originY * dx
        return Glyph(
            String(Character.toChars(code)), code, style(c, code, size), angle, u0, u1, v,
            ExactScripts.isRtl(code), ExactScripts.isStrongLtr(code), loose.min(), loose.max(), origin,
            c.uncertain, c.mcid
        )
    }

    /** A box's four corners projected on the baseline direction ([dx], [dy]). */
    private fun corners(left: Float, bottom: Float, right: Float, top: Float, dx: Float, dy: Float) = floatArrayOf(
        left * dx + bottom * dy,
        right * dx + bottom * dy,
        left * dx + top * dy,
        right * dx + top * dy
    )

    private fun style(c: PdfiumChar, code: Int, size: Float): TextStyle {
        val arabic = ExactScripts.isArabic(code)
        val face = face(c)
        return TextStyle(
            family = face.family,
            arabic = arabic,
            bold = face.bold,
            italic = !arabic && face.italic,
            sizePt = (size * SIZE_STEP).roundToInt() / SIZE_STEP,
            argb = c.argb
        )
    }

    /** What a PDF font maps to; the same for every glyph of that font, so worked out once per font. */
    private class Face(val family: OfficeFamily, val bold: Boolean, val italic: Boolean)

    private data class FaceKey(val font: String, val flags: Int, val weight: Int)

    /**
     * The name tests in [OfficeFamily] lowercase the font name and scan dozens of hints; done per glyph
     * they were a quarter of a 142-page Word conversion on the phone (simpleperf, 2026-11-11).
     */
    private val faces = java.util.concurrent.ConcurrentHashMap<FaceKey, Face>()

    private fun face(c: PdfiumChar): Face {
        val key = FaceKey(c.font, c.fontFlags, c.weight)
        faces[key]?.let { return it }
        if (faces.size >= MAX_FACES) faces.clear()
        return Face(
            OfficeFamily.of(c.font, c.fontFlags),
            OfficeFamily.isBold(c.font, c.fontFlags, c.weight),
            OfficeFamily.isItalic(c.font, c.fontFlags)
        ).also { faces[key] = it }
    }

    private fun angleOf(c: PdfiumChar): Int = c.angle.roundToInt().let {
        if (abs(
                it
            ) <= ROTATION_TOLERANCE_DEG
        ) {
            0
        } else {
            it
        }
    }

    /** The share of the wider glyph's extent the two have in common, on the same baseline. */
    fun overlap(a: Glyph, b: Glyph): Float {
        if (a.angle != b.angle || abs(a.v - b.v) > a.style.sizePt * DUPLICATE_BASELINE) return 0f
        val shared = min(a.u1, b.u1) - max(a.u0, b.u0)
        val width = max(a.u1 - a.u0, b.u1 - b.u0)
        return if (width <= 0f) 0f else shared / width
    }

    fun spaceCentre(c: PdfiumChar): Float {
        val radians = Math.toRadians(angleOf(c).toDouble())
        return ((c.left + c.right) / 2) * cos(radians).toFloat() + ((c.bottom + c.top) / 2) * sin(radians).toFloat()
    }

    private const val ROTATION_TOLERANCE_DEG = 1
    private const val MAX_FACES = 4_096
    private const val SIZE_STEP = 2f
    private const val HALF = 0.5f
    private const val MIN_SIZE_PT = 0.1f
    private const val DUPLICATE_BASELINE = 0.2f
}

/** Unicode script tests the layout stages share. */
internal object ExactScripts {

    fun isArabic(code: Int): Boolean = code in 0x0600..0x06FF || code in 0x0750..0x077F ||
        code in 0x08A0..0x08FF || code in 0xFB50..0xFDFF || code in 0xFE70..0xFEFF

    fun isRtl(code: Int): Boolean = when (Character.getDirectionality(code)) {
        Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> true
        else -> false
    }

    fun isStrongLtr(code: Int): Boolean = Character.getDirectionality(code) == Character.DIRECTIONALITY_LEFT_TO_RIGHT
}
