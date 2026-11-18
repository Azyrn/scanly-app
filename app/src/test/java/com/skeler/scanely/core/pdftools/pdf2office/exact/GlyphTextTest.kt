package com.skeler.scanely.core.pdftools.pdf2office.exact

import com.skeler.scanely.core.pdfeditor.engine.PdfiumChar
import com.skeler.scanely.core.pdfeditor.engine.PdfiumGlyphId
import com.skeler.scanely.core.pdfeditor.engine.PdfiumPage
import com.skeler.scanely.core.pdftools.pdf2office.exact.TrueTypeFixture.Glyph
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Arabic glyph rebuild ([GlyphFont] → [GlyphLetters] → [GlyphText]) on a font shaped like
 * Word's Simplified Arabic subsets: a partial cmap, joined variants that differ from a mapped
 * glyph only in their hints, and letters built from a dotless skeleton plus a dot.
 */
class GlyphTextTest {

    private val program = TrueTypeFixture.build(
        listOf(
            Glyph(box = null, advance = 0),
            Glyph(intArrayOf(60, 0, 140, 700), advance = 200), // 1 alef (cmap)
            Glyph(intArrayOf(60, 0, 140, 700), advance = 200), // 2 alef, joined form (no cmap)
            Glyph(intArrayOf(20, -10, 480, 200), advance = 500), // 3 dotless skeleton
            Glyph(intArrayOf(0, 0, 80, 80), advance = 0), // 4 dot
            Glyph(intArrayOf(20, -150, 480, 200), 500, parts = listOf(part(3, 0, 0), part(4, 210, -150))), // 5 beh
            Glyph(intArrayOf(20, -10, 480, 330), 500, parts = listOf(part(3, 0, 0), part(4, 210, 250))), // 6 noon
            Glyph(intArrayOf(20, -160, 480, 200), 500, parts = listOf(part(3, 0, 0), part(4, 200, -160))) // 7 beh?
        ),
        cmap = mapOf(ALEF to 1, BEH to 5, NOON to 6)
    )

    @Test
    fun fontTablesAreReadAsTheSpecLaysThemOut() {
        val font = GlyphFont.parse(program)!!

        assertEquals(1000, font.unitsPerEm)
        assertEquals(8, font.glyphCount)
        assertEquals(listOf(ALEF), font.codePoints[1])
        assertNull(font.codePoints[2])
        assertEquals(listOf(3 to (0 to 0), 4 to (210 to -150)), font.parts[5]!!.map { it.glyph to (it.dx to it.dy) })
        assertEquals(2, font.contours[5])
        assertNull(font.boxes[0])
        // pdfium's box: the glyph box in 1/1000 em, the top grown by 1/64.
        assertArrayEquals(intArrayOf(60, 0, 140, 710), font.inkBox(1))
    }

    @Test
    fun anythingButTrueTypeIsRejected() {
        assertNull(GlyphFont.parse(ByteArray(0)))
        assertNull(GlyphFont.parse("OTTO and then some bytes".toByteArray()))
        assertNull(GlyphFont.parse(program.copyOf(40)))
    }

    @Test
    fun unmappedGlyphsAreNamedByShapeAndByTheirMarks() {
        val letters = GlyphLetters(GlyphFont.parse(program)!!)

        assertTrue(letters.arabic)
        assertEquals("ا", letters.letters[2]) // same box, advance and contours as the mapped alef
        assertEquals("ب", letters.letters[7]) // the beh skeleton with its dot nearest beh's
    }

    @Test
    fun aWrongTextMappingIsReplacedByTheFontsOwnLetters() {
        // ToUnicode swaps the letters, as in Word's exports; the ink boxes still identify the glyphs.
        val chars = listOf(
            glyphChar(1, BEH, 100f),
            glyphChar(5, ALEF, 102f),
            glyphChar(6, ALEF, 107f),
            glyphChar(2, LAM, 112f)
        )

        val out = GlyphText.recover(PdfiumPage(595f, 842f, chars, fontPrograms = listOf(program)))

        assertEquals(listOf(ALEF, BEH, NOON, ALEF), out.map { it.code })
        assertTrue(out.none { it.uncertain })
    }

    @Test
    fun glyphIdsFromAReadWithoutToUnicodeNameEachGlyphAndRestoreDroppedLetters() {
        // Two alefs in a row mapped to spaces: pdfium kept neither as a letter and dropped the second.
        // The read without ToUnicode has every glyph, at its origin.
        val chars = listOf(glyphChar(5, ALEF, 100f), glyphChar(1, SPACE, 105f), glyphChar(6, ALEF, 109f))
        val ids = listOf(5 to 100f, 1 to 105f, 2 to 107f, 6 to 109f).map { (glyph, x) -> PdfiumGlyphId(glyph, x, BASELINE) }

        val out = GlyphText.recover(PdfiumPage(595f, 842f, chars, fontPrograms = listOf(program), glyphIds = ids))

        assertEquals(listOf(BEH, ALEF, ALEF, NOON), out.map { it.code })
        assertEquals(107f, out[2].originX, 0.001f)
    }

    @Test
    fun aFontWhoseMappingAgreesWithItsCmapIsLeftAlone() {
        val chars = listOf(glyphChar(1, ALEF, 100f), glyphChar(5, BEH, 102f), glyphChar(6, NOON, 107f))
        val page = PdfiumPage(595f, 842f, chars, fontPrograms = listOf(program))

        assertSame(chars, GlyphText.recover(page))
    }

    @Test
    fun anUnreadableFontProgramKeepsThePdfsText() {
        val chars = listOf(glyphChar(1, BEH, 100f), glyphChar(5, ALEF, 102f))
        val page = PdfiumPage(595f, 842f, chars, fontPrograms = listOf(ByteArray(16)))

        assertEquals(listOf(BEH, ALEF), GlyphText.recover(page).map { it.code })
        assertFalse(GlyphText.recover(page).any { it.uncertain })
    }

    private fun part(glyph: Int, dx: Int, dy: Int) = GlyphPart(glyph, dx, dy)

    /** A character drawing [glyph] at 10 pt with origin ([x], 700), reported by the PDF as [code]. */
    private fun glyphChar(glyph: Int, code: Int, x: Float): PdfiumChar {
        val ink = GlyphFont.parse(program)!!.inkBox(glyph)!!
        val em = SIZE / 1000f
        return PdfiumChar(
            code = code, originX = x, originY = BASELINE,
            left = x, bottom = BASELINE - 3f, right = x + 5f, top = BASELINE + 8f,
            size = SIZE, matrixA = 1f, matrixB = 0f, matrixC = 0f, matrixD = 1f,
            font = "BCDEEE+SimplifiedArabic", fontFlags = 0, weight = 400, argb = 0xFF000000.toInt(), flags = 0,
            renderMode = 0,
            inkLeft = x + ink[0] * em, inkBottom = BASELINE + ink[1] * em,
            inkRight = x + ink[2] * em, inkTop = BASELINE + ink[3] * em,
            fontProgram = 0
        )
    }

    private companion object {
        const val ALEF = 0x0627
        const val BEH = 0x0628
        const val NOON = 0x0646
        const val LAM = 0x0644
        const val SPACE = 0x20
        const val SIZE = 10f
        const val BASELINE = 700f
    }
}
