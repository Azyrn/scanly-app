package com.skeler.scanely.core.pdftools.pdf2office.exact

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Builds a minimal TrueType program with the tables an embedded Word subset keeps (head, maxp,
 * hhea, hmtx, loca, glyf, cmap format 4): outlines are glyph headers only, which is all
 * [GlyphFont] reads. Units per em 1000, so font units are thousandths of an em.
 */
internal object TrueTypeFixture {

    /** A glyph: its box (null draws nothing), advance, contour count, and parts for a composite. */
    class Glyph(
        val box: IntArray?,
        val advance: Int,
        val contours: Int = 1,
        val parts: List<GlyphPart> = emptyList()
    )

    fun build(glyphs: List<Glyph>, cmap: Map<Int, Int>): ByteArray {
        val glyf = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        for (glyph in glyphs) {
            offsets += glyf.size()
            val box = glyph.box ?: continue
            DataOutputStream(glyf).run {
                writeShort(if (glyph.parts.isEmpty()) glyph.contours else -1)
                box.forEach { writeShort(it) }
                glyph.parts.forEachIndexed { i, part ->
                    val more = if (i < glyph.parts.lastIndex) MORE_PARTS else 0
                    writeShort(ARGS_ARE_WORDS or ARGS_ARE_XY or more)
                    writeShort(part.glyph)
                    writeShort(part.dx)
                    writeShort(part.dy)
                }
                if (glyph.parts.isEmpty()) writeShort(0)
            }
        }
        offsets += glyf.size()
        val tables = sortedMapOf(
            "cmap" to cmap(cmap),
            "glyf" to glyf.toByteArray(),
            "head" to bytes {
                writeInt(0x00010000)
                repeat(14) { writeByte(0) }
                writeShort(UNITS_PER_EM)
                repeat(30) { writeByte(0) }
                writeShort(1) // long loca offsets
                writeShort(0)
            },
            "hhea" to bytes {
                repeat(34) { writeByte(0) }
                writeShort(glyphs.size)
            },
            "hmtx" to bytes { glyphs.forEach { writeShort(it.advance); writeShort(0) } },
            "loca" to bytes { offsets.forEach { writeInt(it) } },
            "maxp" to bytes {
                writeInt(0x00005000)
                writeShort(glyphs.size)
            }
        )
        val headerSize = 12 + 16 * tables.size
        return bytes {
            writeInt(0x00010000)
            writeShort(tables.size)
            repeat(6) { writeByte(0) }
            var at = headerSize
            for ((tag, data) in tables) {
                writeBytes(tag)
                writeInt(0)
                writeInt(at)
                writeInt(data.size)
                at += data.size
            }
            tables.values.forEach { write(it) }
        }
    }

    /** A format 4 subtable (Windows, Unicode BMP) with one segment per code point. */
    private fun cmap(map: Map<Int, Int>): ByteArray {
        val codes = map.keys.sorted()
        val segments = codes.size + 1
        return bytes {
            writeShort(0)
            writeShort(1)
            writeShort(3)
            writeShort(1)
            writeInt(12)
            writeShort(4)
            writeShort(16 + 8 * segments)
            writeShort(0)
            writeShort(segments * 2)
            repeat(3) { writeShort(0) }
            codes.forEach { writeShort(it) }
            writeShort(0xFFFF)
            writeShort(0)
            codes.forEach { writeShort(it) }
            writeShort(0xFFFF)
            codes.forEach { writeShort((map.getValue(it) - it) and 0xFFFF) }
            writeShort(1)
            repeat(segments) { writeShort(0) }
        }
    }

    private fun bytes(block: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).block() }.toByteArray()

    private const val UNITS_PER_EM = 1000
    private const val ARGS_ARE_WORDS = 0x0001
    private const val ARGS_ARE_XY = 0x0002
    private const val MORE_PARTS = 0x0020
}
