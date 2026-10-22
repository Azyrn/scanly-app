package com.skeler.scanely.core.pdftools

import com.skeler.scanely.core.pdftools.pdf2office.BlockKind
import com.skeler.scanely.core.pdftools.pdf2office.DocxWriter
import com.skeler.scanely.core.pdftools.pdf2office.LayoutBox
import com.skeler.scanely.core.pdftools.pdf2office.LayoutAnalyzer
import com.skeler.scanely.core.pdftools.pdf2office.PageLayout
import com.skeler.scanely.core.pdftools.pdf2office.ParagraphBlock
import com.skeler.scanely.core.pdftools.pdf2office.PptxWriter
import com.skeler.scanely.core.pdftools.pdf2office.TableBlock
import com.skeler.scanely.core.pdftools.pdf2office.XlsxWriter
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
        assertTrue(first.contains("Apples\t1,200"))
        assertFalse(first.contains("<p:pic>"))
        assertTrue(parts.getValue("ppt/slides/slide2.xml").decodeToString().contains("<p:pic>"))
        assertTrue(parts.getValue("ppt/slides/slide3.xml").decodeToString().contains(">dense two</a:t>"))
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
