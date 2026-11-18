package com.skeler.scanely.core.pdftools.pdf2office.exact

import com.skeler.scanely.core.pdfeditor.engine.PdfPageTags
import com.skeler.scanely.core.pdfeditor.engine.PdfStructure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tagged PDF's own structure read into paragraphs ([ExactTags], [ExactParagraphs]), list labels
 * ([ListLabel], [DocxLists]) and running headers ([RunHeaders]), on trees shaped like the ones Word
 * writes: a list item's label apart from its body, a heading, a footnote, an artifact outside the tree.
 */
class ExactParagraphsTest {

    private val style = TextStyle(OfficeFamily.TIMES, arabic = false, bold = false, italic = false, sizePt = 10f, argb = 0)

    /**
     * Document > [ H1 > Span(0) | P > Span(1) | L > LI > [ Lbl(2) | LBody > Span(3) ] | Footnote > P > Span(4) |
     * Table > TR > TD > P > Span(5) ]; the numbers are the page's marked-content ids.
     */
    private val structure = PdfStructure(
        arrayOf(
            "Document", "H1", "Span", "P", "Span", "L", "LI", "Lbl", "LBody", "Span", "Footnote", "P", "Span",
            "Table", "TR", "TD", "P", "Span"
        ),
        intArrayOf(-1, 0, 1, 0, 3, 0, 5, 6, 6, 8, 0, 10, 11, 0, 13, 14, 15, 16),
        arrayOf(intArrayOf(2, 4, 7, 9, 12, 17))
    )
    private val tags: PdfPageTags = structure.page(0)!!

    @Test
    fun aHeadingAParagraphAndAListItemAreNamedByTheTree() {
        val read = ExactTags(tags)

        assertEquals(TagKind.HEADING, read.block(0)!!.kind)
        assertEquals(1, read.block(0)!!.level)
        assertEquals(TagKind.BODY, read.block(1)!!.kind)
        assertEquals(TagKind.LIST, read.block(3)!!.kind)
        assertEquals(TagKind.NOTE, read.block(4)!!.kind)
        assertEquals(TagKind.CELL, read.block(5)!!.kind)
    }

    @Test
    fun aListItemsLabelAndBodyAreOneParagraph() {
        val read = ExactTags(tags)

        assertEquals(read.block(2)!!.node, read.block(3)!!.node)
        assertEquals(5, read.block(3)!!.list)
    }

    @Test
    fun textOutsideTheTreeIsAnArtifact() {
        val read = ExactTags(tags)

        assertNull(read.block(-1))
        assertNull(read.block(40))
    }

    @Test
    fun linesOfOneTaggedParagraphAreOneParagraphWhateverTheirSpacing() {
        // Three lines of one P, the last set apart as a geometric reader would split it; then a heading above.
        val layout = layout(
            line(700f, 72f, 0, "Heading"),
            line(670f, 72f, 1, "First", "line", "of", "the", "paragraph"),
            line(658f, 72f, 1, "second", "line", "of", "it"),
            line(646f, 72f, 1, "third", "line")
        )

        val paras = ExactParagraphs.of(layout)!!

        assertEquals(listOf(1, 3), paras.flow.map { it.lines.size })
        assertEquals(TagKind.HEADING, paras.flow[0].block.kind)
        assertEquals(12f, paras.flow[1].pitchPt, 0.01f)
    }

    @Test
    fun aListItemsMarkerBecomesItsLabelAndLeavesTheText() {
        val layout = layout(
            line(700f, 90f, 2, "3."),
            line(700f, 120f, 3, "Le", "Secrétariat", "considère"),
            line(688f, 72f, 3, "que", "le", "paragraphe")
        )

        val item = ExactParagraphs.of(layout)!!.flow.single()

        assertEquals("3.", item.label!!.tokens.single().text)
        assertEquals("Le", item.lines.first().segments.single().tokens.first().text)
        assertEquals(2, item.lines.size)
    }

    @Test
    fun notesCellsAndArtifactsStayPlaced() {
        val layout = layout(
            line(760f, 72f, -1, "A/79/1"),
            line(700f, 72f, 1, "Body", "text"),
            line(400f, 72f, 5, "cell"),
            line(100f, 72f, 4, "1", "A", "footnote")
        )

        val paras = ExactParagraphs.of(layout)!!

        assertEquals(1, paras.flow.size)
        assertEquals(2, paras.rest.size)
        assertEquals("A/79/1", paras.artifacts.single().lines.single().tokens.single().text)
    }

    @Test
    fun aParagraphWithARightToLeftLineDoesNotFlow() {
        val arabic = Segment(listOf(token("نص", 72f, 3)), 688f, 10f, rtl = true, angleDeg = 0, bidi = true)
        val layout = layout(line(700f, 72f, 1, "Body"), line(676f, 72f, 3, "Latin"), arabic)

        val paras = ExactParagraphs.of(layout)!!

        assertEquals(1, paras.flow.size)
        assertEquals(TagKind.BODY, paras.flow.single().block.kind)
    }

    @Test
    fun anUntaggedPageHasNoParagraphsToFlow() {
        val untagged = ExactPageLayout(612f, 792f, ExactBlocks.blocks(listOf(line(700f, 72f, 1, "Body"))))

        assertNull(ExactParagraphs.of(untagged))
    }

    @Test
    fun markersReadAsNumbersLettersRomansAndBullets() {
        assertEquals("decimal", ListLabel.parse("3.")!!.format)
        assertEquals(3, ListLabel.parse("3.")!!.value)
        assertEquals("%1.", ListLabel.parse("3.")!!.levelText)
        assertEquals("lowerLetter", ListLabel.parse("(b)")!!.format)
        assertEquals("(%1)", ListLabel.parse("(b)")!!.levelText)
        assertEquals("lowerRoman", ListLabel.parse("iv)")!!.format)
        assertEquals(4, ListLabel.parse("iv)")!!.value)
        assertEquals("upperRoman", ListLabel.parse("II.")!!.format)
        assertEquals(ListLabel.BULLET, ListLabel.parse("•")!!.format)
        assertNull(ListLabel.parse("Le"))
        assertNull(ListLabel.parse("2024"))
        assertNull(ListLabel.parse("paragraph."))
    }

    @Test
    fun theLetterIAfterHIsALetterNotARoman() {
        val h = ListLabel.parse("h)")!!

        assertEquals("lowerLetter", ListLabel.parse("i)", h)!!.format)
        assertEquals(9, ListLabel.parse("i)", h)!!.value)
        assertEquals("lowerRoman", ListLabel.parse("i)")!!.format)
    }

    @Test
    fun itemsThatCountOnShareANumberingAndABreakStartsANewOne() {
        val lists = DocxLists()
        fun id(text: String) = lists.numId(lists.label(text, 5, 1)!!, style, 5, 1)

        val three = id("3.")
        val four = id("4.")
        val seven = id("7.")

        assertEquals(three, four)
        assertFalse(seven == four)
        assertTrue(lists.xml().contains("<w:startOverride w:val=\"3\"/>"))
        assertTrue(lists.xml().contains("<w:startOverride w:val=\"7\"/>"))
    }

    @Test
    fun aPageNumberIsToldFromRepeatedTextByThePageBeside() {
        val page = listOf(block(760f, 72f, "A/79/1"), block(40f, 300f, "2/24"), block(40f, 72f, "24-08745"))
        val next = listOf(block(760f, 480f, "A/79/1"), block(40f, 300f, "3/24"), block(40f, 480f, "24-08745"))

        val (repeated, counting) = RunHeaders.split(page, next, 1)

        assertEquals(listOf("A/79/1", "24-08745"), repeated.map { it.lines.single().tokens.single().text })
        assertEquals("2/24", counting.single().lines.single().tokens.single().text)
    }

    @Test
    fun aSectionKeepsOneHeaderForOddAndOneForEvenPages() {
        val odd = listOf(block(760f, 480f, "A/79/1"))
        val even = listOf(block(760f, 72f, "A/79/1"))
        val headers = RunHeaders()
        headers.add(listOf(block(760f, 72f, "United Nations")), first = true, number = 1)
        headers.add(even, first = false, number = 2)
        headers.add(odd, first = false, number = 3)

        assertTrue(headers.accepts(even, first = false, number = 4))
        assertFalse(headers.accepts(odd, first = false, number = 4))
        assertFalse(headers.accepts(listOf(block(760f, 480f, "A/79/2")), first = false, number = 5))
        assertTrue(headers.unmatched(odd + block(40f, 300f, "5/24"), first = false, number = 5).size == 1)
        assertNotNull(headers.first.single())
    }

    private fun token(text: String, u0: Float, mcid: Int, space: Boolean = false) =
        Token(text, style, u0, u0 + text.length * CHAR_PT, space, 0f, mcid = mcid)

    /** Words on one baseline from [left], a space apart, all under the marked content [mcid]. */
    private fun line(baseline: Float, left: Float, mcid: Int, vararg words: String): Segment {
        var x = left
        val tokens = words.mapIndexed { i, word ->
            token(word, x, mcid, space = i > 0).also { x = it.u1 + CHAR_PT / 2 }
        }
        return Segment(tokens, baseline, style.sizePt, rtl = false, angleDeg = 0)
    }

    private fun block(baseline: Float, left: Float, text: String) = ExactBlocks.block(listOf(line(baseline, left, -1, text)))

    private fun layout(vararg segments: Segment) =
        ExactPageLayout(612f, 792f, ExactBlocks.blocks(segments.toList()), tags = tags)

    private companion object {
        const val CHAR_PT = 5f
    }
}
