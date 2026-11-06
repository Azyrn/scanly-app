package com.skeler.scanely.core.pdftools

import com.skeler.scanely.core.ocr.paddle.ReadLine
import com.skeler.scanely.core.pdftools.pdf2office.BlockKind
import com.skeler.scanely.core.pdftools.pdf2office.DocxWriter
import com.skeler.scanely.core.pdftools.pdf2office.LayoutBox
import com.skeler.scanely.core.pdftools.pdf2office.LayoutAnalyzer
import com.skeler.scanely.core.pdftools.pdf2office.PageLayout
import com.skeler.scanely.core.pdftools.pdf2office.ParagraphBlock
import com.skeler.scanely.core.pdftools.pdf2office.PptxWriter
import com.skeler.scanely.core.pdftools.pdf2office.TableBlock
import com.skeler.scanely.core.pdftools.pdf2office.XlsxWriter
import com.skeler.scanely.core.pdftools.pdf2office.exact.ExactDocxWriter
import com.skeler.scanely.core.pdftools.pdf2office.exact.ExactLayout
import com.skeler.scanely.core.pdftools.pdf2office.exact.ExactPageLayout
import com.skeler.scanely.core.pdftools.pdf2office.exact.ExactTable
import com.skeler.scanely.core.pdftools.pdf2office.exact.ExactTableCell
import com.skeler.scanely.core.pdftools.pdf2office.exact.ExactTableRow
import com.skeler.scanely.core.pdftools.pdf2office.exact.ExactTables
import com.skeler.scanely.core.pdftools.pdf2office.exact.OfficeFamily
import com.skeler.scanely.core.pdftools.pdf2office.exact.PagePicture
import com.skeler.scanely.core.pdftools.pdf2office.exact.ScanTextLayout
import com.skeler.scanely.core.pdftools.pdf2office.exact.Segment
import com.skeler.scanely.core.pdftools.pdf2office.exact.TextMeasurer
import com.skeler.scanely.core.pdftools.pdf2office.exact.TextStyle
import com.skeler.scanely.core.pdftools.pdf2office.exact.Token
import com.skeler.scanely.core.pdftools.pdf2office.xmlText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The OOXML writers without a device: every part must parse as XML, every relationship must point
 * at a part that exists, and every part must have a content type — the three things Office checks
 * before it shows a file at all.
 */
class OfficeWritersTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val paragraph = ParagraphBlock(
        BlockKind.BODY,
        "Fish & chips <cost> \"less\"\u0001 than you'd think",
        listOf("Fish & chips <cost> \"less\"\u0001 than you'd think"),
        listOf(LayoutBox(50f, 90f, 300f, 100f)),
        LayoutBox(50f, 90f, 300f, 100f),
        11f
    )
    private val heading = ParagraphBlock(
        BlockKind.HEADING_1,
        "Quarterly figures",
        listOf("Quarterly figures"),
        listOf(LayoutBox(50f, 40f, 250f, 64f)),
        LayoutBox(50f, 40f, 250f, 64f),
        24f
    )
    private val table = TableBlock(
        rows = listOf(listOf("Item", "Qty"), listOf("Apples", "1,200"), listOf("Code", "007")),
        rowBoxes = List(3) { LayoutBox(50f, 120f + it * 16f, 300f, 130f + it * 16f) },
        box = LayoutBox(50f, 120f, 300f, 162f),
        fontSize = 10f
    )
    private val layout = PageLayout(listOf(heading, paragraph, table), positioned = true)

    @Test
    fun `docx has its parts, valid XML, the text and the table`() {
        val image = temp.newFile("page.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val bytes = ByteArrayOutputStream().also { out ->
            DocxWriter(out, 595f, 842f).apply {
                addPage(layout)
                addImagePage(image, 1240, 1754)
                finish()
            }
        }.toByteArray()

        val parts = unzip(bytes)
        assertPackageConsistent(parts)
        val document = parts.getValue("word/document.xml").decodeToString()
        assertTrue(document.contains("<w:pStyle w:val=\"Heading1\"/>"))
        assertTrue(document.contains("Fish &amp; chips &lt;cost&gt;"))
        assertFalse("control character leaked", document.contains('\u0001'))
        assertTrue(document.contains("<w:tbl>"))
        assertTrue(document.contains(">Apples</w:t>"))
        assertTrue(document.contains("<w:br w:type=\"page\"/>"))
        assertTrue(parts.containsKey("word/media/image1.jpg"))
    }

    @Test
    fun `docx table columns take the widths the PDF gave them`() {
        val measured = table.copy(columnWidthsPt = listOf(150f, 100f))
        val bytes = ByteArrayOutputStream().also { out ->
            DocxWriter(out, 595f, 842f).apply {
                addPage(PageLayout(listOf(measured), positioned = true))
                finish()
            }
        }.toByteArray()

        val document = unzip(bytes).getValue("word/document.xml").decodeToString()
        assertTrue(document.contains("<w:tblW w:w=\"5000\" w:type=\"dxa\"/>"))
        assertTrue(document.contains("<w:gridCol w:w=\"3000\"/><w:gridCol w:w=\"2000\"/>"))
    }

    @Test
    fun `exact docx writes a detected table as a native table inside a text box`() {
        val layout = ExactTables.detect(exactTablePage())
        val bytes = ByteArrayOutputStream().also { out ->
            ExactDocxWriter(out, measurer).apply {
                addPage(595f, 842f, layout, background = null)
                finish()
            }
        }.toByteArray()

        val parts = unzip(bytes)
        assertPackageConsistent(parts)
        val document = parts.getValue("word/document.xml").decodeToString()
        assertTrue(document.contains("<w:txbxContent><w:tbl>"))
        assertTrue(document.contains("<w:tblLayout w:type=\"fixed\"/>"))
        assertTrue(document.contains("<w:insideV w:val=\"nil\"/>"))
        assertTrue(document.contains("w:hRule=\"atLeast\""))
        assertTrue("a paragraph must close the text box after the table", document.contains("</w:tbl><w:p>"))
        assertEquals(4, Regex("<w:tr>").findAll(document).count())
        // Drawn once, in the table: none of its text is also in a text box of its own.
        assertEquals(1, Regex(">Apples</w:t>").findAll(document).count())
        assertTrue(document.contains(">Below</w:t>"))
    }

    /**
     * LibreOffice puts a relativeHeight of 0 or 1 on top of everything (tdf#159158) and misorders
     * behind-text values above Int.MAX_VALUE (tdf#120760): the first page's background, drawing 1,
     * covered every picture on that page (1000_0.pdf page 1).
     */
    @Test
    fun `exact docx stacks drawings in document order with values LibreOffice reads right`() {
        val background = temp.newFile("background.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val photo = temp.newFile("photo.jpg").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val bytes = ByteArrayOutputStream().also { out ->
            ExactDocxWriter(out, measurer).apply {
                repeat(2) {
                    addPage(595f, 842f, ExactTables.detect(exactTablePage()), background, listOf(PagePicture(photo, false, 300f, 100f, 200f, 150f)))
                }
                finish()
            }
        }.toByteArray()

        val document = unzip(bytes).getValue("word/document.xml").decodeToString()
        val heights = Regex("relativeHeight=\"(\\d+)\"").findAll(document).map { it.groupValues[1].toLong() }.toList()
        assertTrue(heights.size > 4)
        assertTrue("0 and 1 go on top in LibreOffice: $heights", heights.all { it > 1 })
        assertTrue("behind-text values must fit an Int", heights.all { it <= Int.MAX_VALUE })
        assertEquals("ascending in document order", heights.sorted(), heights)
        assertEquals(heights.size, heights.toSet().size)
    }

    /**
     * irs_p17 tax tables: figures that share one grid column (a wide merged column) keep their own
     * places through tab stops instead of collapsing against each other behind a single space.
     */
    @Test
    fun `segments sharing a table cell keep their positions with tab stops`() {
        val style = TextStyle(OfficeFamily.ARIAL, arabic = false, bold = false, italic = false, sizePt = 10f, argb = 0xFF000000.toInt())
        fun segment(text: String, x: Float) = Segment(listOf(Token(text, style, x, x + text.length * 5f, false, 0f)), 700f, 10f, false, 0)
        // Indented 3 pt into its column: LibreOffice Impress measures tab stops from a paragraph's
        // marL, PowerPoint's own reading is unverified here, so a cell with stops has no marL at all.
        val first = segment("2,285", 103f)
        val second = segment("2,103", 160f)
        val table = ExactTable(
            rows = listOf(ExactTableRow(listOf(ExactTableCell(listOf(segment("21,000", 40f))), ExactTableCell(listOf(first, second))), LayoutBox(40f, 132f, 185f, 144f), 10f)),
            columnWidthsPt = listOf(60f, 85f),
            box = LayoutBox(40f, 132f, 185f, 144f),
            fontSize = 10f
        )
        val layout = ExactPageLayout(595f, 842f, emptyList(), tables = listOf(table))
        val docx = ByteArrayOutputStream().also { out ->
            ExactDocxWriter(out, measurer).apply { addPage(595f, 842f, layout, background = null); finish() }
        }.toByteArray()
        val pptx = ByteArrayOutputStream().also { out ->
            PptxWriter(out, 595f, 842f).apply { addExactSlide(layout, 595f, 842f, null, emptyList(), measurer); finish() }
        }.toByteArray()

        val document = unzip(docx).getValue("word/document.xml").decodeToString()
        // The second column starts at 100 pt; its second figure at 160 pt: 60 pt = 1200 twips in.
        assertTrue(document.contains("<w:tabs><w:tab w:val=\"left\" w:pos=\"1200\"/></w:tabs>"))
        assertTrue(document.contains("<w:ind w:left=\"60\"/>"))
        assertTrue(document.contains("<w:r><w:tab/></w:r>"))
        val slide = unzip(pptx).getValue("ppt/slides/slide1.xml").decodeToString()
        assertTrue(slide.contains("<a:tabLst><a:tab pos=\"${3 * 12_700}\" algn=\"l\"/><a:tab pos=\"${60 * 12_700}\" algn=\"l\"/></a:tabLst>"))
        assertFalse("a cell with tab stops has no indent to measure them from", slide.contains("<a:pPr marL="))
        assertEquals(2, Regex("<a:t>\t</a:t>").findAll(slide).count())

        // irs_p17 page 116: a first segment a hair inside its column rounds to a stop at 0, and a tab
        // to the stop it already stands on jumps to the next one (every segment one column right).
        val edge = ExactTable(
            rows = listOf(ExactTableRow(listOf(ExactTableCell(listOf(segment("At", 100.000_01f), segment("But", 130f)))), LayoutBox(100f, 132f, 160f, 144f), 10f)),
            columnWidthsPt = listOf(60f),
            box = LayoutBox(100f, 132f, 160f, 144f),
            fontSize = 10f
        )
        val edgeSlide = ByteArrayOutputStream().also { out ->
            PptxWriter(out, 595f, 842f).apply {
                addExactSlide(ExactPageLayout(595f, 842f, emptyList(), tables = listOf(edge)), 595f, 842f, null, emptyList(), measurer)
                finish()
            }
        }.toByteArray().let { unzip(it).getValue("ppt/slides/slide1.xml").decodeToString() }
        assertFalse(edgeSlide.contains("<a:tab pos=\"0\""))
        assertEquals(1, Regex("<a:t>\t</a:t>").findAll(edgeSlide).count())
    }

    /**
     * irs_p17 tax tables in LibreOffice Impress: each row height rounded to its 1/100 mm unit drifted
     * the table 1.1 pt down over 75 rows. Row edges are snapped to that unit as running totals, so
     * every height is a whole number of units and the bottom stays where the PDF has it.
     */
    @Test
    fun `pptx table rows add up to the table's height in LibreOffice's units`() {
        val style = TextStyle(OfficeFamily.ARIAL, arabic = false, bold = false, italic = false, sizePt = 7f, argb = 0xFF000000.toInt())
        val rows = (0 until 75).map { i ->
            val baseline = 800f - i * 7.43f
            fun segment(text: String, x: Float) = Segment(listOf(Token(text, style, x, x + 20f, false, 0f)), baseline, 7f, false, 0)
            listOf(segment("3,000", 40f), segment("303", 120f))
        }
        val layout = ExactTables.detect(ExactPageLayout(595f, 842f, rows.flatten().map { ExactLayout.block(listOf(it)) }))
        val bytes = ByteArrayOutputStream().also { out ->
            PptxWriter(out, 595f, 842f).apply { addExactSlide(layout, 595f, 842f, null, emptyList(), measurer); finish() }
        }.toByteArray()

        val slide = unzip(bytes).getValue("ppt/slides/slide1.xml").decodeToString()
        val heights = Regex("<a:tr h=\"(\\d+)\">").findAll(slide).map { it.groupValues[1].toLong() }.toList()
        assertEquals(75, heights.size)
        assertTrue("every row a whole number of 1/100 mm", heights.all { it % 360 == 0L })
        // 75 lines of 7.43 pt: the last row's line is cut to the pitch above it too.
        val expected = 75 * 7.43f * 12_700
        assertEquals(expected.toDouble(), heights.sum().toDouble(), 360.0)
        // A cell whose line fills its row exactly grows it a hair in Impress, and 75 hairs are 1.3 pt.
        val lines = Regex("<a:lnSpc><a:spcPts val=\"(\\d+)\"/>").findAll(slide).map { it.groupValues[1].toInt() }.toList()
        assertTrue("lines under the row height: $lines", lines.all { it < 743 })
    }

    /**
     * irs_p17 page 124: rows as far apart as the text is tall (7 pt at 7 pt). Impress won't set a
     * table row that short and grows every row, so in PowerPoint such a table stays positioned text;
     * Word sets it, so the .docx keeps the native table.
     */
    @Test
    fun `a table too tight for a PowerPoint row stays text on the slide but not in Word`() {
        val style = TextStyle(OfficeFamily.ARIAL, arabic = false, bold = false, italic = false, sizePt = 7f, argb = 0xFF000000.toInt())
        fun segment(text: String, x: Float, baseline: Float) = Segment(listOf(Token(text, style, x, x + 20f, false, 0f)), baseline, 7f, false, 0)
        val segments = (0 until 6).flatMap { i -> listOf(segment("94,600", 40f, 700f - i * 7f), segment("17,035", 120f, 700f - i * 7f)) }
        val layout = ExactTables.detect(ExactPageLayout(595f, 842f, segments.map { ExactLayout.block(listOf(it)) }))
        assertEquals(1, layout.tables.size)
        val pptx = ByteArrayOutputStream().also { out ->
            PptxWriter(out, 595f, 842f).apply { addExactSlide(layout, 595f, 842f, null, emptyList(), measurer); finish() }
        }.toByteArray()
        val docx = ByteArrayOutputStream().also { out ->
            ExactDocxWriter(out, measurer).apply { addPage(595f, 842f, layout, background = null); finish() }
        }.toByteArray()

        val slide = unzip(pptx).getValue("ppt/slides/slide1.xml").decodeToString()
        assertFalse(slide.contains("<a:tbl>"))
        assertEquals(6, Regex(">94,600</a:t>").findAll(slide).count())
        assertTrue(unzip(docx).getValue("word/document.xml").decodeToString().contains("<w:tbl>"))
    }

    /**
     * fed_h8 page 14: 8.98 pt rows written as 180 twips each drifted 0.3 pt down a 15-row table.
     * Row and column edges are rounded as running totals, so the table ends where the PDF's does.
     */
    @Test
    fun `docx table rows and columns add up without drifting`() {
        val style = TextStyle(OfficeFamily.ARIAL, arabic = false, bold = false, italic = false, sizePt = 7.5f, argb = 0xFF000000.toInt())
        val rows = (0 until 40).map { i ->
            val baseline = 800f - i * 8.98f
            fun segment(text: String, x: Float) = Segment(listOf(Token(text, style, x, x + 20f, false, 0f)), baseline, 7.5f, false, 0)
            listOf(segment("Item", 40f), segment("1,228.1", 121.33f), segment("1,250.5", 202.66f))
        }
        val layout = ExactTables.detect(ExactPageLayout(595f, 842f, rows.flatten().map { ExactLayout.block(listOf(it)) }))
        val bytes = ByteArrayOutputStream().also { out ->
            ExactDocxWriter(out, measurer).apply { addPage(595f, 842f, layout, background = null); finish() }
        }.toByteArray()

        val document = unzip(bytes).getValue("word/document.xml").decodeToString()
        val heights = Regex("w:trHeight w:val=\"(\\d+)\"").findAll(document).map { it.groupValues[1].toInt() }.toList()
        assertEquals(40, heights.size)
        // 39 pitches of 8.98 pt to the last row's top, which is all but that row's own line.
        assertEquals(39 * 8.98 * 20, heights.dropLast(1).sum().toDouble(), 1.0)
        val columns = Regex("<w:gridCol w:w=\"(\\d+)\"/>").findAll(document).map { it.groupValues[1].toInt() }.toList()
        assertEquals((202.66 - 40) * 20, columns.dropLast(1).sum().toDouble(), 1.0)
        // A line taller than its row, even by a twip, grows the row: every line fits the row it is in.
        Regex("<w:tr>.*?</w:tr>").findAll(document).forEach { row ->
            val height = Regex("w:trHeight w:val=\"(\\d+)\"").find(row.value)!!.groupValues[1].toInt()
            Regex("w:line=\"(\\d+)\"").findAll(row.value).forEach { assertTrue("line ${it.groupValues[1]} in a $height row", it.groupValues[1].toInt() <= height) }
        }
    }

    /**
     * fed_h8 page 14: an empty cell's paragraph is as tall as its end mark's font in Impress, not its
     * fixed line, so an 8 pt mark grew 9 pt rows to 9.6 pt. Empty cells carry the smallest mark.
     */
    @Test
    fun `an empty pptx table cell asks for no height`() {
        val style = TextStyle(OfficeFamily.ARIAL, arabic = false, bold = false, italic = false, sizePt = 8f, argb = 0xFF000000.toInt())
        fun segment(text: String, x: Float, baseline: Float) = Segment(listOf(Token(text, style, x, x + 20f, false, 0f)), baseline, 8f, false, 0)
        val box = LayoutBox(40f, 100f, 200f, 110f)
        val table = ExactTable(
            rows = listOf(ExactTableRow(listOf(ExactTableCell(emptyList()), ExactTableCell(listOf(segment("plans", 100f, 734f)))), box, 8f)),
            columnWidthsPt = listOf(60f, 100f),
            box = box,
            fontSize = 8f
        )
        val bytes = ByteArrayOutputStream().also { out ->
            PptxWriter(out, 595f, 842f).apply {
                addExactSlide(ExactPageLayout(595f, 842f, emptyList(), tables = listOf(table)), 595f, 842f, null, emptyList(), measurer)
                finish()
            }
        }.toByteArray()

        val slide = unzip(bytes).getValue("ppt/slides/slide1.xml").decodeToString()
        assertTrue(slide.contains("<a:endParaRPr lang=\"en-US\" sz=\"100\"/>"))
        assertFalse(slide.contains("<a:endParaRPr lang=\"en-US\" sz=\"800\"/>"))
    }

    @Test
    fun `a table read from a scan sits behind the page picture`() {
        // Recognised cells as PaddleOCR reports them, on a page image of one pixel per point.
        val grid = listOf(listOf("Item", "Qty", "Price"), listOf("Apples", "12", "3.50"), listOf("Pears", "7", "4.25"))
        val lines = grid.flatMapIndexed { row, cells ->
            cells.mapIndexed { column, text ->
                val left = 72f + column * 130f
                ReadLine(text, 0.9f, left, 100f + row * 14f, left + text.length * 5f, 110f + row * 14f)
            }
        }
        val layout = ExactTables.detect(ScanTextLayout.layout(lines, 595, 842, 595f, 842f))
        val background = temp.newFile("scan.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val bytes = ByteArrayOutputStream().also { out ->
            ExactDocxWriter(out, measurer).apply {
                addPage(595f, 842f, layout, background)
                finish()
            }
        }.toByteArray()

        assertTrue(layout.blocks.isEmpty())
        val document = unzip(bytes).getValue("word/document.xml").decodeToString()
        val table = document.indexOf("<w:tbl>")
        assertTrue("table written before the picture", table in 0 until document.indexOf("name=\"Page background\""))
        val anchor = document.lastIndexOf("<wp:anchor ", table)
        assertTrue(document.substring(anchor, table).contains("behindDoc=\"1\""))
    }

    @Test
    fun `pptx has one slide per page with editable text and a picture only for the scan`() {
        val image = temp.newFile("page.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val bytes = ByteArrayOutputStream().also { out ->
            PptxWriter(out, 842f, 595f).apply {
                addTextSlide(layout, 842f, 595f)
                addImageSlide(842f, 595f, image)
                addTextSlide(LayoutAnalyzer.plainLines("dense one\ndense two"), 595f, 842f)
                finish()
            }
        }.toByteArray()

        val parts = unzip(bytes)
        assertPackageConsistent(parts)
        val presentation = parts.getValue("ppt/presentation.xml").decodeToString()
        assertEquals(3, Regex("<p:sldId ").findAll(presentation).count())
        assertTrue(presentation.contains("<p:sldSz cx=\"${842 * 12_700}\" cy=\"${595 * 12_700}\"/>"))
        val first = parts.getValue("ppt/slides/slide1.xml").decodeToString()
        assertTrue(first.contains("txBox=\"1\""))
        assertTrue(first.contains(">Quarterly figures</a:t>"))
        assertTrue(first.contains("<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/table\"><a:tbl>"))
        assertEquals(3, Regex("<a:tr ").findAll(first).count())
        assertTrue(first.contains(">1,200</a:t>"))
        assertFalse("a table is no longer tab-joined text", first.contains("Apples\t1,200"))
        assertFalse(first.contains("<p:pic>"))
        assertTrue(parts.getValue("ppt/slides/slide2.xml").decodeToString().contains("<p:pic>"))
        assertTrue(parts.getValue("ppt/slides/slide3.xml").decodeToString().contains(">dense two</a:t>"))
    }

    @Test
    fun `exact pptx writes a detected table as a native table`() {
        val layout = ExactTables.detect(exactTablePage())
        val bytes = ByteArrayOutputStream().also { out ->
            PptxWriter(out, 595f, 842f).apply {
                addExactSlide(layout, 595f, 842f, background = null, pictures = emptyList(), measurer = measurer)
                finish()
            }
        }.toByteArray()

        val parts = unzip(bytes)
        assertPackageConsistent(parts)
        val slide = parts.getValue("ppt/slides/slide1.xml").decodeToString()
        assertTrue(slide.contains("<p:graphicFrame>"))
        assertTrue(slide.contains("<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/table\"><a:tbl>"))
        assertEquals(3, Regex("<a:gridCol ").findAll(slide).count())
        assertEquals(4, Regex("<a:tr ").findAll(slide).count())
        assertEquals(1, Regex(">Apples</a:t>").findAll(slide).count())
        assertTrue(slide.contains(">Below</a:t>"))
    }

    @Test
    fun `xlsx stores numbers as numbers and keeps leading zeros as text`() {
        val bytes = ByteArrayOutputStream().also { out ->
            XlsxWriter(out).apply {
                addSheet("Page 1", table.rows.asSequence())
                addSheet("Page 1", sequenceOf(listOf("again")))
                finish("Text")
            }
        }.toByteArray()

        val parts = unzip(bytes)
        assertPackageConsistent(parts)
        val sheet = parts.getValue("xl/worksheets/sheet1.xml").decodeToString()
        assertTrue(sheet.contains("<c r=\"B2\"><v>1200</v></c>"))
        assertTrue(sheet.contains("<c r=\"B3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">007</t>"))
        val workbook = parts.getValue("xl/workbook.xml").decodeToString()
        assertTrue(workbook.contains("name=\"Page 1\""))
        assertTrue(workbook.contains("name=\"Page 1 (2)\""))
    }

    @Test
    fun `xlsx keeps prose on mixed and surrounding pages without duplicating table cells`() {
        val before = paragraph.copy(lines = listOf("Before the table"))
        val after = paragraph.copy(lines = listOf("After the table"))
        val bytes = ByteArrayOutputStream().also { out ->
            XlsxWriter(out).apply {
                addPage("Page 1", PageLayout(listOf(before), positioned = true))
                addPage("Page 2", PageLayout(listOf(before, table, after), positioned = true))
                addPage("Page 3", PageLayout(listOf(after), positioned = true))
                finish("Text")
            }
        }.toByteArray()
        val parts = unzip(bytes)
        assertPackageConsistent(parts)
        val first = parts.getValue("xl/worksheets/sheet1.xml").decodeToString()
        val mixed = parts.getValue("xl/worksheets/sheet2.xml").decodeToString()
        val last = parts.getValue("xl/worksheets/sheet3.xml").decodeToString()
        assertTrue(first.contains(">Before the table</t>"))
        assertTrue(last.contains(">After the table</t>"))
        assertTrue(mixed.indexOf(">Before the table</t>") < mixed.indexOf(">Apples</t>"))
        assertTrue(mixed.indexOf(">Apples</t>") < mixed.indexOf(">After the table</t>"))
        assertEquals(1, Regex(">Apples</t>").findAll(mixed).count())
        assertTrue(mixed.contains("<v>1200</v>"))
    }

    @Test
    fun `an xlsx with no sheets still gets one`() {
        val bytes = ByteArrayOutputStream().also { out -> XlsxWriter(out).finish("Text") }.toByteArray()

        val parts = unzip(bytes)
        assertPackageConsistent(parts)
        assertTrue(parts.containsKey("xl/worksheets/sheet1.xml"))
    }

    @Test
    fun `numbers and column names follow Excel's rules`() {
        assertEquals("1200.5", XlsxWriter.numericValue("1,200.5"))
        assertEquals("-3", XlsxWriter.numericValue("-3"))
        assertEquals("0.25", XlsxWriter.numericValue("0.25"))
        assertNull(XlsxWriter.numericValue("007"))
        assertNull(XlsxWriter.numericValue("12a"))
        assertNull(XlsxWriter.numericValue("1234567890123456"))
        assertEquals("A", XlsxWriter.columnName(0))
        assertEquals("Z", XlsxWriter.columnName(25))
        assertEquals("AA", XlsxWriter.columnName(26))
    }

    @Test
    fun `xml text drops what XML cannot hold`() {
        assertEquals("a&amp;b&lt;c", xmlText("a&b<c\u0000"))
        assertEquals("😀", xmlText("😀"))
        assertEquals("x", xmlText("x\uD83D"))
    }

    private val measurer = TextMeasurer { text, _, _, _, _, sizePt -> text.length * sizePt * 0.5f }

    /** An A4 page with a 4 x 3 table of single-word cells and a line of text under it. */
    private fun exactTablePage(): ExactPageLayout {
        val grid = listOf(
            listOf("Item", "Qty", "Price"),
            listOf("Apples", "12", "3.50"),
            listOf("Pears", "7", "4.25"),
            listOf("Plums", "30", "1.10")
        )
        val style = TextStyle(OfficeFamily.ARIAL, arabic = false, bold = false, italic = false, sizePt = 10f, argb = 0xFF000000.toInt())
        fun segment(text: String, x: Float, baseline: Float) =
            Segment(listOf(Token(text, style, x, x + text.length * 5f, false, 0f)), baseline, 10f, false, 0)
        val cells = grid.flatMapIndexed { row, values ->
            values.mapIndexed { column, value -> segment(value, 72f + column * 130f, 720f - row * 14f) }
        }
        val blocks = (cells + segment("Below", 72f, 640f)).map { ExactLayout.block(listOf(it)) }
        return ExactPageLayout(595f, 842f, blocks)
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val parts = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                assertFalse("duplicate ${entry.name}", parts.containsKey(entry.name))
                parts[entry.name] = zip.readBytes()
            }
        }
        return parts
    }

    private fun assertPackageConsistent(parts: Map<String, ByteArray>) {
        assertTrue(parts.containsKey("[Content_Types].xml"))
        assertTrue(parts.containsKey("_rels/.rels"))
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        parts.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { (name, bytes) ->
            runCatching { factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)) }
                .onFailure { throw AssertionError("$name is not well-formed XML", it) }
        }

        val types = parts.getValue("[Content_Types].xml").decodeToString()
        val defaults = Regex("Extension=\"([^\"]+)\"").findAll(types).map { it.groupValues[1] }.toSet()
        val overrides = Regex("PartName=\"/([^\"]+)\"").findAll(types).map { it.groupValues[1] }.toSet()
        parts.keys.filter { it != "[Content_Types].xml" }.forEach { name ->
            val typed = name in overrides || name.substringAfterLast('.') in defaults
            assertTrue("$name has no content type", typed)
        }
        overrides.forEach { assertTrue("override for missing part $it", parts.containsKey(it)) }

        parts.keys.filter { it.endsWith(".rels") }.forEach { relsName ->
            val owner = relsName.replace("_rels/", "").removeSuffix(".rels")
            val base = owner.substringBeforeLast('/', "")
            val rels = parts.getValue(relsName).decodeToString()
            Regex("Target=\"([^\"]+)\"").findAll(rels).forEach { match ->
                val target = resolve(base, match.groupValues[1])
                assertTrue("$relsName points at missing $target", parts.containsKey(target))
            }
        }
    }

    private fun resolve(base: String, target: String): String {
        val segments = if (base.isEmpty()) mutableListOf() else base.split('/').toMutableList()
        target.split('/').forEach { segment ->
            when (segment) {
                ".." -> segments.removeAt(segments.lastIndex)
                ".", "" -> Unit
                else -> segments += segment
            }
        }
        return segments.joinToString("/")
    }
}
