package com.skeler.scanely.core.pdftools.pdf2office.exact

import com.skeler.scanely.core.pdfeditor.engine.PdfiumChar
import com.skeler.scanely.core.pdfeditor.engine.PdfiumGlyphId
import com.skeler.scanely.core.pdfeditor.engine.PdfiumPage
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Rebuilds a page's Arabic text from its glyphs where the PDF's own text mapping is wrong (Word's
 * exports: see [GlyphLetters]). pdfium gives no glyph number for a character, but it gives the
 * glyph's ink box, and the box identifies the glyph in the embedded font ([GlyphFont.inkBox]).
 *
 * A font is only read this way when its `cmap` contradicts the page's text for part of its
 * characters, so a PDF with a correct mapping keeps its text untouched. A character whose glyph
 * the font cannot name keeps the PDF's code and is flagged [PdfiumChar.uncertain], for OCR to read.
 */
@Suppress("TooManyFunctions")
internal object GlyphText {

    /** A font whose cmap contradicts this share of its characters on a page has a wrong text mapping. */
    private const val MISMAPPED_SHARE = 0.05f
    private const val MIN_MISMAPPED = 2
    private const val THOUSAND = 1000f
    private const val INK_SLACK = 1
    private const val UPRIGHT = 0.01f
    private const val MAX_CACHED_FONTS = 24

    private const val LOAD_FACTOR = 0.75f
    private val cache = object : LinkedHashMap<Long, GlyphLetters?>(MAX_CACHED_FONTS, LOAD_FACTOR, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Long, GlyphLetters?>
        ): Boolean = size > MAX_CACHED_FONTS
    }

    /**
     * One glyph on the page: the characters pdfium gave for it (several for a ligature), and what the
     * font says.
     */
    private class Glyph(val first: Int, val count: Int, val program: Int, val candidates: List<Int>) {
        var letters: String? = null
    }

    /**
     * [page]'s characters with the text of mis-mapped Arabic fonts rebuilt; the same list when there is
     * nothing to do.
     */
    fun recover(page: PdfiumPage): List<PdfiumChar> {
        if (page.fontPrograms.isEmpty()) return page.chars
        // Font programs come from the PDF and are untrusted: whatever goes wrong reading one, the page keeps
        // the text pdfium gave. One font at a time: the per-font tables are shared between pages.
        return synchronized(cache) { runCatching { rebuild(page) }.getOrDefault(page.chars) }
    }

    private fun rebuild(page: PdfiumPage): List<PdfiumChar> {
        val fonts = page.fontPrograms.map(::lettersOf)
        if (fonts.all { it == null }) return page.chars
        val ids = page.glyphIds.associate { key(it.originX, it.originY) to it.glyph }
        val glyphs = glyphs(page.chars, fonts, ids)
        val wrong = mismapped(page.chars, glyphs, fonts)
        if (wrong.isEmpty()) return page.chars
        // With the glyph each character draws, the letters pdfium dropped are the glyphs it read only
        // without the ToUnicode map; without it, holes between glyphs are measured instead.
        val dropped = if (ids.isEmpty()) {
            dropped(page.chars, glyphs, fonts, wrong)
        } else {
            unmapped(page, glyphs, fonts, wrong)
        }
        val out = ArrayList<PdfiumChar>(page.chars.size)
        var next = 0
        for (glyph in glyphs) {
            while (next < glyph.first) out += page.chars[next++]
            next = glyph.first + glyph.count
            val chars = page.chars.subList(glyph.first, next)
            if (glyph.program !in wrong) out += chars else out += rebuilt(chars, glyph, fonts[glyph.program]!!)
            dropped[glyph.first]?.let { out += it }
        }
        while (next < page.chars.size) out += page.chars[next++]
        return out
    }

    /**
     * A character's place on its line: where it starts, how far it advances, and its glyph when the font
     * names it.
     */
    private class Cell(val char: PdfiumChar, val index: Int, val advance: Float, val glyph: Glyph?)

    /**
     * The letters pdfium left out. Where the PDF's mapping calls two glyphs in a row a space, pdfium
     * keeps one space and drops the other character altogether. Glyphs of one run abut, so a dropped
     * glyph leaves a hole exactly as wide as its advance: when one letter among the glyphs known to
     * be mapped to a space ([GlyphLetters.spaceMapped]) has that advance, it goes back in the hole.
     * Keyed by the index of the character each restored letter follows.
     */
    private fun dropped(
        chars: List<PdfiumChar>,
        glyphs: List<Glyph>,
        fonts: List<GlyphLetters?>,
        wrong: Set<Int>
    ): Map<Int, List<PdfiumChar>> {
        learnSpaceMapped(chars, glyphs, fonts, wrong)
        val out = HashMap<Int, List<PdfiumChar>>()
        for (cells in lineCells(chars, glyphs, fonts)) {
            cells.sortBy { it.char.originX }
            val spacing = lineSpacing(cells)
            for ((a, b) in cells.zipWithNext()) {
                val program = a.char.fontProgram
                val same = b.char.fontProgram == program && a.char.size == b.char.size
                val restored = if (program in wrong && same) restore(a, b, fonts[program]!!, spacing) else null
                if (restored != null) out[a.glyph?.first ?: a.index] = listOf(restored)
            }
        }
        return out
    }

    /** The glyphs of mis-mapped fonts that the PDF's mapping calls a space. */
    private fun learnSpaceMapped(
        chars: List<PdfiumChar>,
        glyphs: List<Glyph>,
        fonts: List<GlyphLetters?>,
        wrong: Set<Int>
    ) {
        for (glyph in glyphs) {
            if (glyph.program in wrong && glyph.letters != null && chars[glyph.first].code == SPACE) {
                fonts[glyph.program]!!.spaceMapped += glyph.candidates
            }
        }
    }

    /** Each line's first characters of glyphs (by baseline), with their advance on the page. */
    private fun lineCells(
        chars: List<PdfiumChar>,
        glyphs: List<Glyph>,
        fonts: List<GlyphLetters?>
    ): Collection<MutableList<Cell>> {
        val byFirst = glyphs.associateBy { it.first }
        val lines = HashMap<Int, MutableList<Cell>>()
        chars.forEachIndexed { i, c ->
            if (c.generated || (i > 0 && sameGlyph(chars[i - 1], c))) return@forEachIndexed
            val glyph = byFirst[i]
            val font = fonts.getOrNull(c.fontProgram)?.font
            val em = c.size * hypot(c.matrixA, c.matrixB)
            val advances = glyph?.candidates?.map { font!!.advances[it] }?.distinct()
            val advance = if (font != null && advances?.size == 1) {
                advances[0] * em / font.unitsPerEm
            } else {
                c.right - c.left
            }
            lines.getOrPut((c.originY * BASELINE_STEPS).roundToInt()) { ArrayList() } += Cell(c, i, advance, glyph)
        }
        return lines.values
    }

    /**
     * Word sets a character spacing on justified lines: every glyph of the line sits that much off its
     * neighbour, the dropped one too. The usual step between two named glyphs is that spacing.
     */
    private fun lineSpacing(cells: List<Cell>): Float {
        val steps = cells.zipWithNext().filter { (a, b) -> a.glyph != null && b.glyph != null }
            .map { (a, b) -> b.char.originX - a.char.originX - a.advance }.sorted()
        return if (steps.isEmpty()) 0f else steps[steps.size / 2]
    }

    /** The letter dropped between [a] and [b], when the hole between them fits exactly one space-mapped glyph. */
    private fun restore(a: Cell, b: Cell, letters: GlyphLetters, spacing: Float): PdfiumChar? {
        val em = a.char.size * hypot(a.char.matrixA, a.char.matrixB)
        val start = a.char.originX + a.advance + spacing
        val hole = (b.char.originX - start - spacing) / em * letters.font.unitsPerEm
        if (hole < MIN_HOLE_UNITS) return null
        val slack = letters.font.unitsPerEm * HOLE_SLACK_EM
        val fitting = letters.spaceMapped.filter { abs(letters.font.advances[it] - hole) <= slack }
        val letter = fitting.mapNotNull { letters.letters[it] }.distinct().singleOrNull() ?: return null
        val code = letter.codePoints().toArray().singleOrNull() ?: letters.ligatureCodes[letter] ?: return null
        val ink = letters.font.inkBox(fitting.first()) ?: return null
        val ey = a.char.size * hypot(a.char.matrixC, a.char.matrixD)
        val box = floatArrayOf(
            start + ink[GlyphFont.LEFT] * em / THOUSAND,
            a.char.originY + ink[GlyphFont.BOTTOM] * ey / THOUSAND,
            start + ink[GlyphFont.RIGHT] * em / THOUSAND,
            a.char.originY + ink[GlyphFont.TOP] * ey / THOUSAND
        )
        return a.char.beside(code, start, hole * em / letters.font.unitsPerEm, box)
    }

    private fun lettersOf(program: ByteArray): GlyphLetters? {
        val key = program.size.toLong().shl(Int.SIZE_BITS) or (program.contentHashCode().toLong() and 0xFFFFFFFFL)
        if (cache.containsKey(key)) return cache[key]
        val letters = GlyphFont.parse(program)?.let(::GlyphLetters)?.takeIf { it.arabic }
        cache[key] = letters
        return letters
    }

    /**
     * The drawn characters of readable fonts, grouped into glyphs: characters at one origin are one
     * glyph.
     */
    private fun glyphs(chars: List<PdfiumChar>, fonts: List<GlyphLetters?>, ids: Map<Long, Int>): List<Glyph> {
        val out = ArrayList<Glyph>()
        val found = HashMap<Pair<Int, Long>, List<Int>>()
        var i = 0
        while (i < chars.size) {
            val c = chars[i]
            val font = fonts.getOrNull(c.fontProgram)
            val ink = if (font == null || c.generated) null else ink(c)
            if (ink == null) {
                i++
                continue
            }
            var count = 1
            while (i + count < chars.size && sameGlyph(c, chars[i + count])) count++
            val key = c.fontProgram to ink.fold(0L) { acc, v -> acc * SIGNATURE_BASE + (v + SIGNATURE_BIAS) }
            val known = ids[key(c.originX, c.originY)]?.takeIf { it in 0 until font!!.font.glyphCount }
            val candidates = if (known != null) listOf(known) else found.getOrPut(key) { candidates(font!!.font, ink) }
            out += Glyph(i, count, c.fontProgram, candidates)
            i += count
        }
        return out
    }

    /** A character's origin as a lookup key, to the hundredth of a point. */
    private fun key(x: Float, y: Float): Long =
        (x * ORIGIN_STEPS).roundToInt().toLong().shl(Int.SIZE_BITS) or
            ((y * ORIGIN_STEPS).roundToInt().toLong() and LOW_WORD)

    /**
     * The letters pdfium dropped from the page's text: a character the read without ToUnicode has at an
     * origin where the page's own read has none. pdfium keeps one of two consecutive characters it maps
     * to a space; the glyph read maps nothing to a space, so it keeps both. Each goes in after the
     * character of a mis-mapped font left of it on its baseline, keyed by that character's index.
     */
    private fun unmapped(
        page: PdfiumPage,
        glyphs: List<Glyph>,
        fonts: List<GlyphLetters?>,
        wrong: Set<Int>
    ): Map<Int, List<PdfiumChar>> {
        val chars = page.chars
        val seen = chars.mapTo(HashSet()) { key(it.originX, it.originY) }
        val firstOf = HashMap<Int, Int>()
        glyphs.forEach { glyph -> repeat(glyph.count) { firstOf[glyph.first + it] = glyph.first } }
        val out = HashMap<Int, MutableList<PdfiumChar>>()
        for (id in page.glyphIds.sortedBy { it.originX }) {
            val left = if (key(id.originX, id.originY) in seen) null else leftOf(chars, id, wrong)
            val restored = left?.let { restored(chars[it], id, fonts[chars[it].fontProgram]!!) }
            if (left == null || restored == null) continue
            out.getOrPut(firstOf[left] ?: left) { ArrayList() } += restored
        }
        return out
    }

    /** The index of the character of a mis-mapped font nearest left of glyph [id] on its baseline. */
    private fun leftOf(chars: List<PdfiumChar>, id: PdfiumGlyphId, wrong: Set<Int>): Int? =
        chars.indices.filter {
            val c = chars[it]
            !c.generated && c.fontProgram in wrong && c.originY == id.originY && c.originX < id.originX
        }.maxByOrNull { chars[it].originX }

    /** Glyph [id] as a letter of [letters]' font beside [neighbour]; null for a glyph that draws nothing. */
    private fun restored(neighbour: PdfiumChar, id: PdfiumGlyphId, letters: GlyphLetters): PdfiumChar? {
        val font = letters.font
        if (id.glyph !in 0 until font.glyphCount) return null
        val ink = font.inkBox(id.glyph) ?: return null
        val letter = letters.letters[id.glyph]
        val code = letter?.let { it.codePoints().toArray().singleOrNull() ?: letters.ligatureCodes[it] }
        val em = neighbour.size * hypot(neighbour.matrixA, neighbour.matrixB)
        val ey = neighbour.size * hypot(neighbour.matrixC, neighbour.matrixD)
        val box = floatArrayOf(
            id.originX + ink[GlyphFont.LEFT] * em / THOUSAND,
            id.originY + ink[GlyphFont.BOTTOM] * ey / THOUSAND,
            id.originX + ink[GlyphFont.RIGHT] * em / THOUSAND,
            id.originY + ink[GlyphFont.TOP] * ey / THOUSAND
        )
        val advance = font.advances[id.glyph] * em / font.unitsPerEm
        val char = neighbour.beside(code ?: UNKNOWN_LETTER, id.originX, advance, box)
        return if (code != null) char else char.withCode(char.code, char.flags or PdfiumChar.FLAG_UNCERTAIN)
    }

    private fun sameGlyph(a: PdfiumChar, b: PdfiumChar): Boolean =
        !b.generated && a.fontProgram == b.fontProgram && a.originX == b.originX && a.originY == b.originY

    /**
     * The character's ink box relative to its origin in 1/1000 em, or null for a blank or turned
     * character.
     */
    private fun ink(c: PdfiumChar): IntArray? {
        val ex = c.size * hypot(c.matrixA, c.matrixB)
        val ey = c.size * hypot(c.matrixC, c.matrixD)
        if (!upright(c) || ex <= 0f || ey <= 0f) return null
        val box = intArrayOf(
            ((c.inkLeft - c.originX) / ex * THOUSAND).roundToInt(),
            ((c.inkBottom - c.originY) / ey * THOUSAND).roundToInt(),
            ((c.inkRight - c.originX) / ex * THOUSAND).roundToInt(),
            ((c.inkTop - c.originY) / ey * THOUSAND).roundToInt()
        )
        // A space has no ink (pdfium reports a hairline for it).
        val blank = box[GlyphFont.RIGHT] - box[GlyphFont.LEFT] <= INK_SLACK ||
            box[GlyphFont.TOP] - box[GlyphFont.BOTTOM] <= INK_SLACK
        return if (blank) null else box
    }

    private fun upright(c: PdfiumChar): Boolean =
        c.matrixA > 0f && c.matrixD > 0f &&
            abs(c.matrixB) <= UPRIGHT * c.matrixA && abs(c.matrixC) <= UPRIGHT * c.matrixD

    /** The glyphs of [font] with this ink box: exactly, else to within a rounding step. */
    private fun candidates(font: GlyphFont, ink: IntArray): List<Int> {
        val boxes = (0 until font.glyphCount).mapNotNull { glyph -> font.inkBox(glyph)?.let { glyph to it } }
        val exact = boxes.filter { sameInk(it.second, ink, 0) }.map { it.first }
        return exact.ifEmpty { boxes.filter { sameInk(it.second, ink, INK_SLACK) }.map { it.first } }
    }

    private fun text(chars: List<PdfiumChar>): String =
        Normalizer.normalize(chars.joinToString("") { String(Character.toChars(it.code)) }, Normalizer.Form.NFKC)

    /** The one text every candidate glyph stands for, or null when they disagree or one is unknown. */
    private fun agreed(glyph: Glyph, font: GlyphLetters): String? {
        if (glyph.candidates.isEmpty()) return null
        val letters = glyph.candidates.map { font.letters[it] }.distinct()
        return letters.singleOrNull()
    }

    /** The font programs whose cmap contradicts the page's text. */
    private fun mismapped(chars: List<PdfiumChar>, glyphs: List<Glyph>, fonts: List<GlyphLetters?>): Set<Int> {
        val known = IntArray(fonts.size)
        val wrong = IntArray(fonts.size)
        for (glyph in glyphs) {
            val letters = agreed(glyph, fonts[glyph.program]!!) ?: continue
            glyph.letters = letters
            known[glyph.program]++
            if (letters != text(chars.subList(glyph.first, glyph.first + glyph.count))) wrong[glyph.program]++
        }
        return fonts.indices.filterTo(HashSet()) {
            wrong[it] >= MIN_MISMAPPED && wrong[it] >= known[it] * MISMAPPED_SHARE
        }
    }

    /** The characters of one glyph of a mis-mapped font, as the font reads it. */
    private fun rebuilt(chars: List<PdfiumChar>, glyph: Glyph, font: GlyphLetters): List<PdfiumChar> {
        val first = chars.first()
        val letters = glyph.letters
        if (letters != null) {
            val codes = letters.codePoints().toArray()
            val ligature = if (codes.size > 1) font.ligatureCodes[letters] else null
            return if (ligature != null) listOf(first.withCode(ligature)) else codes.map { first.withCode(it) }
        }
        // A glyph the font cannot name keeps the PDF's text, flagged for OCR ([ExactOcrWords]) unless
        // that text is one the candidates are known to stand for.
        val given = text(chars)
        val named = given in glyph.candidates.mapNotNull { font.letters[it] }
        if (named) return chars
        // Mapped to a space, a drawn letter would split its word in two: it stays in the word as a
        // joining stroke (tatweel), which says "a letter is here" and nothing about which.
        return chars.map {
            it.withCode(if (it.code == SPACE) UNKNOWN_LETTER else it.code, it.flags or PdfiumChar.FLAG_UNCERTAIN)
        }
    }

    private const val SPACE = 0x20
    private const val LOW_WORD = 0xFFFFFFFFL

    /** Stands for a drawn letter the font cannot name: Arabic tatweel, flagged uncertain. */
    private const val UNKNOWN_LETTER = 0x0640
    private const val ORIGIN_STEPS = 100f
    private const val BASELINE_STEPS = 20f
    private const val MIN_HOLE_UNITS = 16f

    /** A hole fits a glyph when it is within this much of an em of the glyph's advance. */
    private const val HOLE_SLACK_EM = 0.004f
    private const val SIGNATURE_BASE = 65_536L
    private const val SIGNATURE_BIAS = 32_768
}
