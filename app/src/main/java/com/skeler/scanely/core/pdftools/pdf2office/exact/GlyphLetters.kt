package com.skeler.scanely.core.pdftools.pdf2office.exact

import java.text.Normalizer

/**
 * Which letters each glyph of a font stands for, worked out from the font program alone. Word
 * embeds Arabic fonts as subsets that keep the original glyph numbers and a partial `cmap`, and
 * writes a ToUnicode map that is wrong for many glyphs; this is the font's own account of them.
 *
 * In order of trust:
 * 1. the `cmap`: a glyph with a code point is that character (a presentation form folds to its
 *    letters, a lam-alef ligature to both);
 * 2. shape: a glyph with the same box, advance and contours as a known one, or built from the same
 *    parts at the same offsets, is the same letter (fonts carry joined variants that only differ in
 *    their hints);
 * 3. parallel composites: a glyph that puts the same mark at the same offset as a known glyph of
 *    the same advance is that letter, and the two skeletons are the same letter without the mark;
 * 4. a known skeleton with a known mark: the known composite on that skeleton whose mark sits nearest.
 *
 * What is left is unknown here: [GlyphText] flags it for OCR.
 */
internal class GlyphLetters(val font: GlyphFont) {

    /** Per glyph: its text, or null when the font does not say. */
    val letters: Array<String?> = arrayOfNulls(font.glyphCount)

    /** The font maps Arabic letters: only such fonts are worth reading glyph by glyph. */
    val arabic: Boolean

    /**
     * The single code point the font's cmap has for a text of several letters (a lam-alef ligature's
     * presentation form): written as that one character, the layout reads it as it reads any PDF's.
     */
    val ligatureCodes = HashMap<String, Int>()

    /** Glyphs the PDF's text mapping calls a space although they draw a letter, as pages show them. */
    val spaceMapped: MutableSet<Int> = HashSet()

    private val keys = HashMap<Int, String>()
    private val fromCmap = BooleanArray(font.glyphCount)

    init {
        var anyArabic = false
        for ((glyph, codes) in font.codePoints) {
            val code = codes.minWith(compareBy({ rank(it) }, { it }))
            anyArabic = anyArabic || codes.any(ExactScripts::isArabic)
            val text = String(Character.toChars(code))
            val letter = if (ExactScripts.isArabic(code)) Normalizer.normalize(text, Normalizer.Form.NFKC) else text
            letters[glyph] = letter
            if (letter.length > 1) ligatureCodes.putIfAbsent(letter, code)
            fromCmap[glyph] = true
        }
        arabic = anyArabic
        if (arabic) {
            bySameShape()
            byParallelComposite()
            byNearestMark()
            byAlias()
        }
    }

    /** A plain letter before a presentation form before any other Arabic code point before the rest. */
    private fun rank(code: Int): Int = when (code) {
        in 0x0621..0x064A -> 0
        in 0xFE70..0xFEFF -> 1
        in 0x0600..0x06FF -> 2
        else -> RANK_OTHER
    }

    private fun drawn(): List<Int> = (0 until font.glyphCount).filter { font.boxes[it] != null }

    /** A glyph's shape: its box, advance and contours, or for a composite its parts' shapes and offsets. */
    private fun key(glyph: Int, depth: Int = 0): String = keys.getOrPut(glyph) {
        val parts = font.parts[glyph]
        if (parts != null && depth < MAX_DEPTH) {
            parts.joinToString(";", "c:") { "${key(it.glyph, depth + 1)}@${it.dx},${it.dy}" }
        } else {
            "s:" + font.boxes[glyph]?.joinToString(",") + "/" + font.advances[glyph] + "/" + font.contours[glyph]
        }
    }

    private fun bySameShape() {
        val known = HashMap<String, MutableSet<String>>()
        drawn().forEach { glyph -> letters[glyph]?.let { known.getOrPut(key(glyph)) { HashSet() } += it } }
        drawn().forEach { glyph -> if (letters[glyph] == null) letters[glyph] = known[key(glyph)]?.singleOrNull() }
    }

    private fun marksKey(parts: List<GlyphPart>, glyph: Int): String =
        parts.drop(1).joinToString(";") { "${key(it.glyph)}@${it.dx},${it.dy}" } + "/" + font.advances[glyph]

    /** Drawn composites as (glyph, parts), read lazily so a pass sees the letters it has just set. */
    private fun composites(): Sequence<Pair<Int, List<GlyphPart>>> =
        drawn().asSequence().mapNotNull { glyph -> font.parts[glyph]?.let { glyph to it } }

    /** Composites the cmap names, with their letter. */
    private fun namedComposites(): Sequence<Triple<Int, List<GlyphPart>, String>> =
        composites().filter { fromCmap[it.first] }.mapNotNull { (glyph, parts) ->
            letters[glyph]?.let { Triple(glyph, parts, it) }
        }

    private fun byParallelComposite() {
        // The marks of every composite the cmap knows, with its letter and skeleton.
        val known = HashMap<String, MutableList<Pair<String, Int>>>()
        namedComposites().filter { it.second.size >= 2 }.forEach { (glyph, parts, letter) ->
            known.getOrPut(marksKey(parts, glyph)) { ArrayList() } += letter to parts[0].glyph
        }
        composites().filter { (glyph, parts) -> letters[glyph] == null && parts.size >= 2 }.forEach { (glyph, parts) ->
            val twins = known[marksKey(parts, glyph)]
            val letter = twins?.map { it.first }?.distinct()?.singleOrNull()
            if (twins != null && letter != null) nameParallel(glyph, parts[0].glyph, letter, twins.first().second)
        }
        font.parts.forEachIndexed { glyph, parts -> if (parts != null) keys.remove(glyph) }
    }

    private fun nameParallel(glyph: Int, skeleton: Int, letter: String, twin: Int) {
        letters[glyph] = letter
        if (letters[skeleton] == null) letters[skeleton] = letters[twin]
        // Other composites on this skeleton are now composites on a known shape.
        keys[skeleton] = key(twin)
    }

    private fun byNearestMark() {
        val known = HashMap<String, MutableList<Triple<Int, Int, String>>>()
        namedComposites().filter { it.second.size == 2 }.forEach { (_, parts, letter) ->
            known.getOrPut(key(parts[0].glyph) + "+" + key(parts[1].glyph)) { ArrayList() } +=
                Triple(parts[1].dx, parts[1].dy, letter)
        }
        composites().filter { (glyph, parts) -> letters[glyph] == null && parts.size == 2 }.forEach { (glyph, parts) ->
            val mark = parts[1]
            letters[glyph] = known[key(parts[0].glyph) + "+" + key(mark.glyph)]?.minByOrNull {
                val dx = (it.first - mark.dx).toLong()
                val dy = (it.second - mark.dy).toLong()
                dx * dx + dy * dy
            }?.third
        }
    }

    /** A composite of one part is that part. */
    private fun byAlias() {
        for (glyph in drawn()) {
            val parts = font.parts[glyph] ?: continue
            if (letters[glyph] == null && parts.size == 1) letters[glyph] = letters[parts[0].glyph]
        }
    }

    private companion object {
        const val MAX_DEPTH = 6
        const val RANK_OTHER = 3
    }
}
