package com.skeler.scanely.core.pdftools.pdf2office.exact

import com.skeler.scanely.core.ocr.paddle.ReadLine

/**
 * The text of a scanned page as a hidden layer: one single-line block per recognised line, at the
 * line's place on the page, behind the page's picture. The page keeps the picture's exact look and
 * the document still holds, and Word still searches, what it says. (A fully transparent text fill
 * would do the same in Word, but LibreOffice ignores the transparency and draws the text white.)
 */
object ScanTextLayout {

    private const val TEXT_COLOUR = 0xFF000000.toInt()
    private const val SIZE_OF_LINE_HEIGHT = 0.8f
    private const val DESCENT_OF_LINE_HEIGHT = 0.2f
    private const val LINE_FACTOR = 1.2f
    private const val MIN_LINE_PT = 2f

    /** [lines] are in the pixels of a [pixelsWide] x [pixelsHigh] image of the whole page. */
    fun layout(
        lines: List<ReadLine>,
        pixelsWide: Int,
        pixelsHigh: Int,
        widthPt: Float,
        heightPt: Float
    ): ExactPageLayout {
        val sx = widthPt / pixelsWide
        val sy = heightPt / pixelsHigh
        val blocks = lines.mapNotNull { line ->
            val lineHeight = (line.bottom - line.top) * sy
            if (lineHeight < MIN_LINE_PT || line.right <= line.left) return@mapNotNull null
            val size = lineHeight * SIZE_OF_LINE_HEIGHT
            val rtlLetters = line.text.codePoints().filter(::isRtl).count()
            val ltrLetters = line.text.codePoints().filter { Character.isLetter(it) && !isRtl(it) }.count()
            val rtl = rtlLetters > ltrLetters
            val style = TextStyle(
                family = OfficeFamily.ARIAL,
                arabic = rtlLetters > 0,
                bold = false,
                italic = false,
                sizePt = size,
                argb = TEXT_COLOUR
            )
            val token = Token(line.text.trim(), style, line.left * sx, line.right * sx, false, 0f)
            val baseline = heightPt - (line.bottom * sy - lineHeight * DESCENT_OF_LINE_HEIGHT)
            val segment = Segment(listOf(token), baseline, size, rtl, 0, bidi = rtlLetters > 0)
            Block(listOf(segment), size * LINE_FACTOR, 0f)
        }
        // Tables are found here, not by the caller, so the app and the host fast loop read scans alike.
        return ExactTables.detect(ExactPageLayout(widthPt, heightPt, blocks, behindPicture = true))
    }

    private fun isRtl(code: Int): Boolean = when (Character.getDirectionality(code)) {
        Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> true
        else -> false
    }
}
