package com.skeler.scanely.core.pdftools.office2pdf.docx

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import java.text.Normalizer

/**
 * The advance widths of an Arabic font the app cannot bundle, so its text can be drawn in another
 * font at the width the document's own font gives it. No free font is metric-compatible with Word's
 * "Simplified Arabic" (LibreOffice and others substitute look-alikes such as Noto Naskh Arabic, about
 * 11 % wider), and Arabic lines are often exact-height, so their count follows the text's width alone.
 *
 * The widths come from Word's own PDFs of the oracle pairs (`scripts/fidelity/simplified_arabic_widths.py`
 * writes the asset): each shaped form's advance in em, regular and bold. A run is shaped into
 * presentation forms (the forms the font draws) and their advances are summed.
 */
internal class ArabicAdvances(private val regular: Map<Int, Float>, private val bold: Map<Int, Float>) {

    /**
     * The width of [shaped] text (presentation forms, [shape]) at [sizePt]; a character the table does
     * not hold (a Latin digit, a mark) counts as [other] gives it, in points.
     */
    fun width(shaped: CharSequence, sizePt: Float, bold: Boolean, other: (Int) -> Float): Float {
        val table = if (bold) this.bold else regular
        var width = 0f
        var i = 0
        while (i < shaped.length) {
            val cp = Character.codePointAt(shaped, i)
            width += table[cp]?.let { it * sizePt } ?: if (ArabicForms.isMark(cp)) 0f else other(cp)
            i += Character.charCount(cp)
        }
        return width
    }

    companion object {
        private const val FIELDS = 3
        private const val HEX = 16

        /** "code point hex <TAB> regular em <TAB> bold em [<TAB> source]" lines; "#" lines are comments. */
        fun parse(lines: Sequence<String>): ArabicAdvances {
            val regular = HashMap<Int, Float>()
            val bold = HashMap<Int, Float>()
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull(::row).forEach { (cp, widths) ->
                regular[cp] = widths.first
                bold[cp] = widths.second
            }
            return ArabicAdvances(regular, bold)
        }

        /** One table line: its code point and (regular, bold) advances; null when malformed. */
        private fun row(line: String): Pair<Int, Pair<Float, Float>>? {
            val f = line.split('\t')
            if (f.size < FIELDS) return null
            val cp = f[0].toIntOrNull(HEX) ?: return null
            val regular = f[1].toFloatOrNull() ?: return null
            val bold = f[2].toFloatOrNull() ?: return null
            return cp to (regular to bold)
        }

        /** [text] in the presentation forms its letters take in context, lam-alef as one ligature. */
        fun shape(text: String): String = ArabicForms.shape(text)
    }
}

/**
 * Arabic contextual shaping, enough to know which form each letter is drawn in: a letter joins the one
 * before it when that one joins forward (it has an initial form) and it has a final form itself; it joins
 * forward when it has an initial form and the next letter can join back. Marks are transparent. The forms
 * and which letters have them are Unicode's own (presentation forms, their names and NFKC decompositions).
 */
internal object ArabicForms {
    private const val ISOLATED = 0
    private const val FINAL = 1
    private const val INITIAL = 2
    private const val MEDIAL = 3
    private const val LAM = 0x0644

    /** No letter (past the end of the text). */
    private const val NONE = -1
    private val RANGES = listOf(0xFB50..0xFDFF, 0xFE70..0xFEFF)
    private val KINDS = listOf(
        "ISOLATED FORM" to ISOLATED,
        "FINAL FORM" to FINAL,
        "INITIAL FORM" to INITIAL,
        "MEDIAL FORM" to MEDIAL
    )

    /** Letter -> its forms by kind (0 where it has none); lam + alef letter -> the ligature's forms. */
    private val forms = HashMap<Int, IntArray>()
    private val lamAlef = HashMap<Int, IntArray>()

    init {
        RANGES.forEach { range -> range.forEach(::register) }
    }

    /** Files presentation form [cp] under the letter (or lam + alef) it is a form of. */
    private fun register(cp: Int) {
        val name = Character.getName(cp) ?: return
        val kind = KINDS.firstOrNull { name.endsWith(it.first) }?.second ?: return
        val points = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFKC).codePoints().toArray()
        val table = when {
            points.size == 1 -> forms.getOrPut(points[0]) { IntArray(KINDS.size) }
            points.size == 2 && points[0] == LAM -> lamAlef.getOrPut(points[1]) { IntArray(KINDS.size) }
            else -> return
        }
        if (table[kind] == 0) table[kind] = cp
    }

    fun shape(text: String): String {
        val points = text.codePoints().toArray()
        val out = StringBuilder(text.length)
        var i = 0
        while (i < points.size) {
            val (form, used) = formAt(points, i)
            out.appendCodePoint(form)
            i += used
        }
        return out.toString()
    }

    /** The form of the letter at [i] and how many code points it stands for (2 for lam-alef). */
    private fun formAt(points: IntArray, i: Int): Pair<Int, Int> {
        val cp = points[i]
        val own = forms[cp] ?: return cp to 1
        val back = joinsForward(previous(points, i)) && own[FINAL] != 0
        val next = next(points, i)
        val after = if (next >= 0) points[next] else NONE
        val ligature = if (cp == LAM) lamAlef[after] else null
        if (ligature != null) return pick(ligature, if (back) FINAL else ISOLATED) to next + 1 - i
        val ahead = own[INITIAL] != 0 && joinsBack(after)
        return (pick(own, kindOf(back, ahead)).takeIf { it != 0 } ?: cp) to 1
    }

    private fun joinsBack(cp: Int): Boolean = forms[cp]?.let { it[FINAL] != 0 } == true

    private fun kindOf(back: Boolean, ahead: Boolean): Int = when {
        back && ahead -> MEDIAL
        back -> FINAL
        ahead -> INITIAL
        else -> ISOLATED
    }

    /** A joining form that exists: medial falls back to final, initial to isolated. */
    private fun pick(own: IntArray, kind: Int): Int = when {
        own[kind] != 0 -> own[kind]
        kind == MEDIAL -> own[FINAL]
        kind == INITIAL -> own[ISOLATED]
        else -> own[ISOLATED]
    }

    private fun joinsForward(cp: Int?): Boolean =
        cp != null && forms[cp]?.let { it[INITIAL] != 0 || it[MEDIAL] != 0 } == true

    fun isMark(cp: Int): Boolean = isArabicMark(cp)
}

private fun isArabicMark(cp: Int): Boolean = Character.getType(cp) == Character.NON_SPACING_MARK.toInt()

/** The letter before index [i], marks skipped; null at the start. */
private fun previous(points: IntArray, i: Int): Int? {
    var j = i - 1
    while (j >= 0 && isArabicMark(points[j])) j--
    return if (j >= 0) points[j] else null
}

/** The index of the letter after [i], marks skipped; -1 at the end. */
private fun next(points: IntArray, i: Int): Int {
    var j = i + 1
    while (j < points.size && isArabicMark(points[j])) j++
    return if (j < points.size) j else -1
}

/**
 * Joined-script (Arabic) runs drawn in a stand-in font at the width of the document's own font, when its
 * widths are known ([DocxFonts.advances]); [paint] makes a run's paint.
 */
internal class JoinedRuns(private val fonts: DocxFonts, private val newPaint: (RunStyle) -> TextPaint) {
    /**
     * A joined (Arabic) run's look: as the platform sets it, scaled as a whole to the width the document's
     * font gives it when that font's widths are known ([DocxFonts.advances]) — the run is drawn in another
     * font. A uniform scale keeps the letters joined; spacing between them would not.
     */
    fun look(style: RunStyle, text: String): GlyphLook {
        val advances = fonts.advances(style.font) ?: return GlyphLook.JOINED
        val paint = newPaint(style).apply { GlyphLook.JOINED.applyTo(this) }
        // Spaces are set apart ([spaceWidths]): the letters alone take the scale.
        val words = text.split(' ').filter { it.isNotEmpty() }
        val measured = words.sumOf { paint.measureText(it).toDouble() }.toFloat()
        if (measured <= 0f) return GlyphLook.JOINED
        val target = words.sumOf { word ->
            advances.width(ArabicAdvances.shape(word), paint.textSize, style.bold) { cp ->
                paint.measureText(String(Character.toChars(cp)))
            }.toDouble()
        }.toFloat()
        return GlyphLook.joined(target / measured * style.scale / PERCENT)
    }

    /**
     * The spaces of a joined run from [start] at the width the document's font gives them. Scaled with the
     * letters, a narrow stand-in space read back as no space at all (pdfium joined the words either side).
     */
    fun spaceWidths(text: SpannableStringBuilder, start: Int, style: RunStyle) {
        val advances = fonts.advances(style.font) ?: return
        val paint = newPaint(style).apply { GlyphLook.JOINED.applyTo(this) }
        val natural = paint.measureText(" ")
        if (natural <= 0f) return
        val own = advances.width(" ", paint.textSize, style.bold) { natural }
        for (i in start until text.length) {
            if (text[i] == ' ') text.setSpan(SpaceScale(own / natural), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private companion object {
        const val PERCENT = 100f
    }
}
