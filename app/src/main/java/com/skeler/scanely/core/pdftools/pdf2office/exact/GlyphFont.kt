package com.skeler.scanely.core.pdftools.pdf2office.exact

import java.nio.ByteBuffer
import kotlin.math.abs

/** One part of a composite glyph: the glyph it draws and where it puts it, in font units. */
internal class GlyphPart(val glyph: Int, val dx: Int, val dy: Int)

/**
 * What a TrueType font program says about its glyphs, read from the tables an embedded subset
 * keeps (head, maxp, hhea, hmtx, loca, glyf, cmap): each glyph's box, advance, contour count and
 * parts, and the code points its `cmap` gives it. No shaping tables are needed, and Word's subsets
 * have none. Tables are read with bounds checks: font data comes from the PDF and is untrusted.
 */
internal class GlyphFont private constructor(
    val unitsPerEm: Int,
    /** Per glyph: xMin, yMin, xMax, yMax in font units; null for a glyph that draws nothing. */
    val boxes: Array<IntArray?>,
    val advances: IntArray,
    val contours: IntArray,
    val parts: Array<List<GlyphPart>?>,
    /** Glyph to the code points the font's own cmap maps to it. */
    val codePoints: Map<Int, List<Int>>
) {
    val glyphCount: Int get() = boxes.size

    /**
     * The glyph's ink box as pdfium reports a character's (FPDFText_GetCharBox), in 1/1000 em:
     * left, bottom, right, top. pdfium scales FreeType's unscaled metrics with
     * (m * 1000 + unitsPerEm / 2) / unitsPerEm, truncated, and adds 1/64 to the top
     * (CPDF_CIDFont::GetCharBBox).
     */
    fun inkBox(glyph: Int): IntArray? {
        val box = boxes.getOrNull(glyph) ?: return null
        val top = thousandths(box[TOP])
        return intArrayOf(
            thousandths(box[LEFT]),
            thousandths(box[BOTTOM]),
            thousandths(box[RIGHT]),
            top + top / TOP_GROWTH
        )
    }

    private fun thousandths(units: Int): Int =
        ((units * THOUSAND + unitsPerEm / 2).toDouble() / unitsPerEm).toInt()

    @Suppress("TooManyFunctions")
    companion object {
        const val LEFT = 0
        const val BOTTOM = 1
        const val RIGHT = 2
        const val TOP = 3
        private const val THOUSAND = 1000
        private const val TOP_GROWTH = 64

        private const val TRUETYPE = 0x00010000
        private const val TRUE_TAG = 0x74727565
        private const val HEADER = 12
        private const val RECORD = 16
        private const val MAX_TABLES = 64
        private const val MAX_GLYPHS = 65_536
        private const val MAX_PARTS = 16
        private const val MAX_DEPTH = 6

        // Composite glyph flags (OpenType spec, glyf table).
        private const val ARGS_ARE_WORDS = 0x0001
        private const val ARGS_ARE_XY = 0x0002
        private const val HAS_SCALE = 0x0008
        private const val MORE_PARTS = 0x0020
        private const val HAS_XY_SCALE = 0x0040
        private const val HAS_MATRIX = 0x0080

        // Field offsets (OpenType spec): table directory, head, maxp, hhea, glyf, cmap.
        private const val NUM_TABLES = 4
        private const val TAG_LENGTH = 4
        private const val RECORD_OFFSET = 8
        private const val RECORD_LENGTH = 12
        private const val HEAD_UNITS_PER_EM = 18
        private const val HEAD_LOCA_FORMAT = 50
        private const val MAXP_GLYPHS = 4
        private const val HHEA_METRICS = 34
        private const val LONG_METRIC = 4
        private const val GLYF_BOX = 2
        private const val GLYF_PARTS = 10
        private const val BOX_VALUES = 4
        private const val PART_HEADER = 4
        private const val LONG_OFFSET = 4
        private const val CMAP_HEADER = 4
        private const val CMAP_RECORD = 8
        private const val CMAP_RECORD_OFFSET = 4
        private const val PLATFORM_UNICODE = 0
        private const val PLATFORM_WINDOWS = 3
        private const val WINDOWS_BMP = 1
        private const val WINDOWS_FULL = 10
        private const val FORMAT_SEGMENTS = 4
        private const val FORMAT_GROUPS = 12
        private const val F4_SEGMENTS = 6
        private const val F4_ENDS = 14
        private const val F12_GROUPS = 12
        private const val F12_HEADER = 16
        private const val F12_GROUP = 12
        private const val F12_END = 4
        private const val F12_GLYPH = 8
        private const val U16_MAX = 0xFFFF
        private val REQUIRED = listOf("head", "maxp", "hhea", "hmtx", "loca", "glyf", "cmap")

        /** Null when [data] is not a TrueType program with outlines and a Unicode cmap. */
        fun parse(data: ByteArray): GlyphFont? = runCatching { read(ByteBuffer.wrap(data)) }.getOrNull()

        private fun read(b: ByteBuffer): GlyphFont? {
            val tables = tables(b) ?: return null
            val head = tables.getValue("head")[0]
            val unitsPerEm = u16(b, head + HEAD_UNITS_PER_EM)
            val glyphs = u16(b, tables.getValue("maxp")[0] + MAXP_GLYPHS)
            if (unitsPerEm <= 0 || glyphs <= 0 || glyphs > MAX_GLYPHS) return null
            val metrics = u16(b, tables.getValue("hhea")[0] + HHEA_METRICS).coerceAtLeast(1)
            val hmtx = tables.getValue("hmtx")[0]
            val advances = IntArray(glyphs) { u16(b, hmtx + LONG_METRIC * minOf(it, metrics - 1)) }
            val outlines = Outlines(glyphs)
            outlines.read(b, tables, b.getShort(head + HEAD_LOCA_FORMAT).toInt() != 0)
            val contours = IntArray(glyphs) { contours(it, outlines.own, outlines.parts, 0) }
            val codePoints = codePoints(b, tables.getValue("cmap")[0], glyphs)
            return GlyphFont(unitsPerEm, outlines.boxes, advances, contours, outlines.parts, codePoints)
        }

        /** Table tag to (offset, length); null unless every table [read] needs is there. */
        private fun tables(b: ByteBuffer): Map<String, IntArray>? {
            val version = b.getInt(0)
            if (version != TRUETYPE && version != TRUE_TAG) return null
            val tables = HashMap<String, IntArray>()
            repeat(u16(b, NUM_TABLES).coerceAtMost(MAX_TABLES)) { i ->
                val at = HEADER + i * RECORD
                val tag = String(ByteArray(TAG_LENGTH) { b.get(at + it) }, Charsets.ISO_8859_1)
                tables[tag] = intArrayOf(b.getInt(at + RECORD_OFFSET), b.getInt(at + RECORD_LENGTH))
            }
            return tables.takeIf { it.keys.containsAll(REQUIRED) }
        }

        /** Each glyph's box, own contour count and composite parts, from loca and glyf. */
        private class Outlines(val glyphs: Int) {
            val boxes = arrayOfNulls<IntArray>(glyphs)
            val own = IntArray(glyphs)
            val parts = arrayOfNulls<List<GlyphPart>>(glyphs)

            fun read(b: ByteBuffer, tables: Map<String, IntArray>, longLoca: Boolean) {
                val loca = tables.getValue("loca")[0]
                val glyf = tables.getValue("glyf")
                for (g in 0 until glyphs) {
                    val start = glyphOffset(b, loca, g, longLoca)
                    val end = glyphOffset(b, loca, g + 1, longLoca)
                    if (end <= start || end > glyf[1]) continue
                    val at = glyf[0] + start
                    val count = b.getShort(at).toInt()
                    boxes[g] = IntArray(BOX_VALUES) { b.getShort(at + GLYF_BOX + 2 * it).toInt() }
                    if (count >= 0) own[g] = count else parts[g] = parts(b, at + GLYF_PARTS, glyphs)
                }
            }
        }

        private fun u16(b: ByteBuffer, at: Int): Int = b.getShort(at).toInt() and U16_MAX

        private fun glyphOffset(b: ByteBuffer, loca: Int, glyph: Int, long: Boolean): Int =
            if (long) b.getInt(loca + LONG_OFFSET * glyph) else u16(b, loca + 2 * glyph) * 2

        private fun parts(b: ByteBuffer, start: Int, glyphs: Int): List<GlyphPart> {
            val out = ArrayList<GlyphPart>()
            var at = start
            do {
                val flags = u16(b, at)
                val glyph = u16(b, at + 2)
                at += PART_HEADER
                val words = flags and ARGS_ARE_WORDS != 0
                val first = if (words) b.getShort(at).toInt() else b.get(at).toInt()
                val second = if (words) b.getShort(at + 2).toInt() else b.get(at + 1).toInt()
                at += if (words) 2 * 2 else 2
                at += transformLength(flags)
                val xy = flags and ARGS_ARE_XY != 0
                if (glyph < glyphs) out += GlyphPart(glyph, if (xy) first else 0, if (xy) second else 0)
            } while (flags and MORE_PARTS != 0 && out.size < MAX_PARTS)
            return out
        }

        /** Bytes of the scale or matrix that follows a composite part's offsets. */
        private fun transformLength(flags: Int): Int = when {
            flags and HAS_SCALE != 0 -> 2
            flags and HAS_XY_SCALE != 0 -> 2 * 2
            flags and HAS_MATRIX != 0 -> 2 * 2 * 2
            else -> 0
        }

        private fun contours(glyph: Int, own: IntArray, parts: Array<List<GlyphPart>?>, depth: Int): Int {
            val list = parts[glyph] ?: return own[glyph]
            if (depth >= MAX_DEPTH) return 0
            return list.sumOf { contours(it.glyph, own, parts, depth + 1) }
        }

        /** Glyph to code points, from the font's Unicode subtables (formats 4 and 12). */
        private fun codePoints(b: ByteBuffer, cmap: Int, glyphs: Int): Map<Int, List<Int>> {
            val out = HashMap<Int, MutableList<Int>>()
            fun add(code: Int, glyph: Int) {
                if (glyph in 1 until glyphs) out.getOrPut(glyph) { ArrayList() }.let { if (code !in it) it += code }
            }
            repeat(u16(b, cmap + 2).coerceAtMost(MAX_TABLES)) { i ->
                val record = cmap + CMAP_HEADER + CMAP_RECORD * i
                if (!isUnicode(u16(b, record), u16(b, record + 2))) return@repeat
                val at = cmap + b.getInt(record + CMAP_RECORD_OFFSET)
                when (u16(b, at)) {
                    FORMAT_SEGMENTS -> format4(b, at, ::add)
                    FORMAT_GROUPS -> format12(b, at, ::add)
                }
            }
            return out
        }

        private fun isUnicode(platform: Int, encoding: Int): Boolean =
            platform == PLATFORM_UNICODE ||
                (platform == PLATFORM_WINDOWS && (encoding == WINDOWS_BMP || encoding == WINDOWS_FULL))

        private fun format4(b: ByteBuffer, at: Int, add: (Int, Int) -> Unit) {
            val segments = u16(b, at + F4_SEGMENTS) / 2
            val ends = at + F4_ENDS
            val starts = ends + 2 * segments + 2
            val deltas = starts + 2 * segments
            val offsets = deltas + 2 * segments
            for (s in 0 until segments) {
                val end = u16(b, ends + 2 * s)
                val start = u16(b, starts + 2 * s)
                val delta = u16(b, deltas + 2 * s)
                val offset = u16(b, offsets + 2 * s)
                if (start == U16_MAX || end < start) continue
                for (code in start..end) {
                    val glyph = if (offset == 0) {
                        (code + delta) and U16_MAX
                    } else {
                        val raw = u16(b, offsets + 2 * s + offset + 2 * (code - start))
                        if (raw == 0) 0 else (raw + delta) and U16_MAX
                    }
                    add(code, glyph)
                }
            }
        }

        private fun format12(b: ByteBuffer, at: Int, add: (Int, Int) -> Unit) {
            repeat(b.getInt(at + F12_GROUPS).coerceIn(0, MAX_GLYPHS)) { g ->
                val group = at + F12_HEADER + F12_GROUP * g
                val start = b.getInt(group)
                val end = b.getInt(group + F12_END)
                val glyph = b.getInt(group + F12_GLYPH)
                if (end < start || end - start > MAX_GLYPHS) return@repeat
                for (code in start..end) add(code, glyph + (code - start))
            }
        }
    }
}

/** Whether two ink boxes in 1/1000 em are the same to within [slack]. */
internal fun sameInk(a: IntArray, b: IntArray, slack: Int): Boolean = a.indices.all { abs(a[it] - b[it]) <= slack }
