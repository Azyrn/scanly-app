package com.skeler.scanely.core.pdftools

import com.skeler.scanely.core.pdftools.office2pdf.docx.DocxDocument
import com.skeler.scanely.core.pdftools.office2pdf.docx.DocxImage
import com.skeler.scanely.core.pdftools.office2pdf.docx.DocxPageBreak
import com.skeler.scanely.core.pdftools.office2pdf.docx.DocxParagraph
import com.skeler.scanely.core.pdftools.office2pdf.docx.DocxReader
import com.skeler.scanely.core.pdftools.office2pdf.docx.DocxTable
import com.skeler.scanely.core.pdftools.office2pdf.docx.DocxText
import com.skeler.scanely.core.pdftools.office2pdf.docx.VerticalMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.io.Reader
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

/**
 * The .docx reader on the JVM, against fixtures written here. The platform pull parser only
 * exists on a device, so the reader is handed one built on the JDK's StAX reader; everything
 * else — the zip, relationships, style chains, numbering, fields — is the production code.
 */
class DocxReaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `style chains resolve and direct formatting wins`() {
        val document = read(
            body = """
                <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Quarterly report</w:t></w:r></w:p>
                <w:p>
                  <w:r><w:rPr><w:b/></w:rPr><w:t>Bold words</w:t></w:r>
                  <w:r><w:t xml:space="preserve"> and plain</w:t></w:r>
                  <w:r><w:rPr><w:rFonts w:ascii="Consolas"/><w:color w:val="FF0000"/></w:rPr><w:t>code</w:t></w:r>
                </w:p>
            """,
            styles = STYLES
        )

        val (heading, body) = paragraphs(document)
        val title = heading.texts().single()
        assertEquals("Quarterly report", title.text)
        assertTrue(title.style.bold)
        assertEquals(32, title.style.halfPoints)
        assertEquals(240, heading.style.spaceBefore)

        val runs = body.texts()
        assertEquals(listOf("Bold words", " and plain", "code"), runs.map { it.text })
        assertTrue(runs[0].style.bold)
        assertFalse(runs[1].style.bold)
        assertEquals(22, runs[1].style.halfPoints)
        assertEquals("Calibri", runs[1].style.font)
        assertEquals("Consolas", runs[2].style.font)
        assertEquals(0xFF0000, runs[2].style.color)
    }

    @Test
    fun `numbered and bulleted lists get their labels and indents`() {
        val document = read(
            body = listItem("One", 1, 0) + listItem("Two", 1, 0) + listItem("Nested", 1, 1) +
                listItem("Three", 1, 0) + listItem("Dot", 2, 0),
            styles = STYLES,
            numbering = NUMBERING
        )

        val items = paragraphs(document)
        assertEquals(listOf("1.", "2.", "a)", "3.", "•"), items.map { it.label?.text })
        assertEquals(720, items[0].style.indentLeft)
        assertEquals(-360, items[0].style.firstLine)
        assertEquals(1440, items[2].style.indentLeft)
        assertNull("bullet symbol fonts are dropped with the symbol", items[4].label?.style?.font)
    }

    @Test
    fun `a table keeps its grid, spans, merges, shading and borders`() {
        val document = read(body = TABLE, styles = STYLES)

        val table = document.sections.single().blocks.single() as DocxTable
        assertEquals(listOf(2000, 2000, 2000), table.columns)
        assertEquals(3, table.rows.size)
        val header = table.rows[0].cells
        assertEquals(2, header[0].span)
        assertEquals(0xD9E2F3, header[0].fill)
        assertTrue("TableGrid draws borders", header.all { it.bordered })
        assertEquals(VerticalMerge.RESTART, table.rows[1].cells[0].merge)
        assertEquals(VerticalMerge.CONTINUE, table.rows[2].cells[0].merge)
        val b2 = table.rows[1].cells[1].blocks.single() as DocxParagraph
        assertEquals("B2", b2.texts().single().text)
        assertEquals("table style spacing applies inside cells", 0, b2.style.spaceAfter)
    }

    @Test
    fun `a page break splits the paragraph and sections keep their own page`() {
        val document = read(
            body = """
                <w:p><w:r><w:t>Before</w:t><w:br w:type="page"/><w:t>After</w:t></w:r></w:p>
                <w:p>
                  <w:pPr><w:sectPr><w:pgSz w:w="12240" w:h="15840"/></w:sectPr></w:pPr>
                  <w:r><w:t>End of first section</w:t></w:r>
                </w:p>
                <w:p><w:r><w:t>Landscape</w:t></w:r></w:p>
                <w:sectPr>
                  <w:pgSz w:w="11906" w:h="16838" w:orient="landscape"/>
                  <w:pgMar w:top="720" w:bottom="720" w:left="1080" w:right="1080"/>
                  <w:type w:val="nextPage"/>
                </w:sectPr>
            """
        )

        assertEquals(2, document.sections.size)
        val first = document.sections[0].blocks
        assertEquals(DocxPageBreak, first[1])
        assertEquals(listOf("Before"), (first[0] as DocxParagraph).texts().map { it.text })
        assertEquals(listOf("After"), (first[2] as DocxParagraph).texts().map { it.text })
        assertEquals(4, first.size)
        val landscape = document.sections[1].page
        assertEquals(16838, landscape.widthTwips)
        assertEquals(11906, landscape.heightTwips)
        assertEquals(1080, landscape.marginLeft)
    }

    @Test
    fun `pictures are kept, charts and shapes are counted, fallbacks win`() {
        val picture = drawing(inline = true, uri = PICTURE_URI, inner = "<a:blip r:embed=\"rIdImg\"/>")
        val chart = drawing(inline = true, uri = CHART_URI, inner = "<c:chart r:id=\"rIdChart\"/>")
        val textBox = drawing(inline = false, uri = SHAPE_URI, inner = "<wps:wsp/>")
        val document = read(
            body = """
                <w:p><w:r>$picture</w:r></w:p>
                <w:p><w:r>$chart</w:r></w:p>
                <w:p><w:r>$textBox</w:r></w:p>
                <w:p><w:r>
                  <mc:AlternateContent>
                    <mc:Choice Requires="wps">$textBox</mc:Choice>
                    <mc:Fallback><w:pict><v:shape style="width:72pt;height:36pt">
                      <v:imagedata r:id="rIdImg"/>
                    </v:shape></w:pict></mc:Fallback>
                  </mc:AlternateContent>
                </w:r></w:p>
                <w:p><m:oMathPara><m:oMath><m:r><m:t>x</m:t></m:r></m:oMath></m:oMathPara></w:p>
            """,
            extraRels = """<Relationship Id="rIdImg" Type="$REL_IMAGE" Target="media/image1.png"/>""",
            extraParts = mapOf("word/media/image1.png" to byteArrayOf(1, 2, 3))
        )

        val images = paragraphs(document).flatMap { it.inlines }.filterIsInstance<DocxImage>()
        assertEquals(2, images.size)
        assertEquals("word/media/image1.png", images[0].entry)
        assertEquals(914_400L, images[0].widthEmu)
        assertEquals(72 * 12_700L, images[1].widthEmu)
        assertEquals(1, document.notes.charts)
        assertEquals("the Choice branch lost to the Fallback and left no count", 1, document.notes.shapes)
        assertEquals(1, document.notes.equations)
    }

    @Test
    fun `fields show their cached result and revisions show the final text`() {
        val document = read(
            body = """
                <w:p>
                  <w:r><w:fldChar w:fldCharType="begin"/></w:r>
                  <w:r><w:instrText>PAGE</w:instrText></w:r>
                  <w:r><w:fldChar w:fldCharType="separate"/></w:r>
                  <w:r><w:t>7</w:t></w:r>
                  <w:r><w:fldChar w:fldCharType="end"/></w:r>
                  <w:fldSimple w:instr="DATE"><w:r><w:t>today</w:t></w:r></w:fldSimple>
                  <w:del><w:r><w:delText>removed</w:delText></w:r></w:del>
                  <w:ins><w:r><w:t>added</w:t></w:r></w:ins>
                  <w:r><w:rPr><w:vanish/></w:rPr><w:t>hidden</w:t></w:r>
                  <w:sdt><w:sdtPr/><w:sdtContent><w:r><w:t>control</w:t></w:r></w:sdtContent></w:sdt>
                  <w:r><w:footnoteReference w:id="1"/></w:r>
                </w:p>
            """
        )

        assertEquals("7todayaddedcontrol", paragraphs(document).single().texts().joinToString("") { it.text })
        assertTrue(document.notes.footnotes)
    }

    @Test
    fun `malformed document xml is damaged`() {
        assertDamaged { read(body = "<w:p><w:r><w:t>never closed") }
    }

    @Test
    fun `a zip without a document is damaged`() {
        val file = zip("empty.docx", mapOf("hello.txt" to "hi".toByteArray()))
        assertDamaged { ZipFile(file).use { DocxReader(::StaxPullParser).read(it) } }
    }

    @Test
    fun `broken styles cost formatting, not the document`() {
        val document = read(body = "<w:p><w:r><w:t>Still here</w:t></w:r></w:p>", styles = "<w:styles><w:style>")

        assertEquals("Still here", paragraphs(document).single().texts().single().text)
    }

    @Test
    fun `a basedOn loop doesn't hang`() {
        val styles = """
            <w:styles $NAMESPACES>
              <w:style w:type="paragraph" w:styleId="A"><w:basedOn w:val="B"/><w:rPr><w:b/></w:rPr></w:style>
              <w:style w:type="paragraph" w:styleId="B"><w:basedOn w:val="A"/><w:rPr><w:i/></w:rPr></w:style>
            </w:styles>
        """
        val body = """<w:p><w:pPr><w:pStyle w:val="A"/></w:pPr><w:r><w:t>x</w:t></w:r></w:p>"""
        val document = read(body = body, styles = styles)

        val run = paragraphs(document).single().texts().single()
        assertTrue(run.style.bold)
        assertTrue(run.style.italic)
    }

    @Test
    fun `absurdly deep nesting is damaged rather than a stack overflow`() {
        val depth = 2_000
        val body = "<w:sdt><w:sdtContent>".repeat(depth) + "</w:sdtContent></w:sdt>".repeat(depth)
        assertDamaged { read(body = body) }
    }

    // --- fixtures ---

    private fun read(
        body: String,
        styles: String? = null,
        numbering: String? = null,
        extraRels: String = "",
        extraParts: Map<String, ByteArray> = emptyMap()
    ): DocxDocument {
        val parts = LinkedHashMap<String, ByteArray>()
        parts["[Content_Types].xml"] = CONTENT_TYPES.toByteArray()
        parts["_rels/.rels"] = ROOT_RELS.toByteArray()
        parts["word/document.xml"] = "<w:document $NAMESPACES><w:body>$body</w:body></w:document>".toByteArray()
        val rels = StringBuilder(extraRels)
        if (styles != null) {
            parts["word/styles.xml"] = styles.toByteArray()
            rels.append("""<Relationship Id="rIdStyles" Type="$REL_STYLES" Target="styles.xml"/>""")
        }
        if (numbering != null) {
            parts["word/numbering.xml"] = numbering.toByteArray()
            rels.append("""<Relationship Id="rIdNumbering" Type="$REL_NUMBERING" Target="numbering.xml"/>""")
        }
        parts["word/_rels/document.xml.rels"] = "<Relationships xmlns=\"$PKG_RELS\">$rels</Relationships>".toByteArray()
        parts.putAll(extraParts)
        val file = zip("fixture-${System.nanoTime()}.docx", parts)
        return ZipFile(file).use { DocxReader(::StaxPullParser).read(it) }
    }

    private fun zip(name: String, parts: Map<String, ByteArray>): File {
        val file = folder.newFile(name)
        ZipOutputStream(file.outputStream()).use { out ->
            parts.forEach { (path, bytes) ->
                out.putNextEntry(ZipEntry(path))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }

    private fun paragraphs(document: DocxDocument): List<DocxParagraph> =
        document.sections.flatMap { it.blocks }.filterIsInstance<DocxParagraph>()

    private fun DocxParagraph.texts(): List<DocxText> = inlines.filterIsInstance<DocxText>()

    private fun assertDamaged(block: () -> Unit) {
        try {
            block()
            fail("expected Damaged")
        } catch (e: ToolException) {
            assertEquals(ToolFailure.Damaged, e.failure)
        }
    }

    private fun listItem(text: String, numId: Int, level: Int) = """
        <w:p><w:pPr><w:numPr><w:ilvl w:val="$level"/><w:numId w:val="$numId"/></w:numPr></w:pPr>
        <w:r><w:t>$text</w:t></w:r></w:p>
    """

    private fun drawing(inline: Boolean, uri: String, inner: String): String {
        val tag = if (inline) "wp:inline" else "wp:anchor"
        return """
            <w:drawing><$tag><wp:extent cx="914400" cy="457200"/>
            <a:graphic><a:graphicData uri="$uri">$inner</a:graphicData></a:graphic></$tag></w:drawing>
        """
    }

    /**
     * Just enough of XmlPullParser for the reader: namespace-aware start/end tags with depth,
     * text, and attributes. Depth follows the pull-parser rule — an end tag reports the depth of
     * its start tag, and the decrement lands on the next event.
     */
    private class StaxPullParser : XmlPullParser {
        private lateinit var reader: XMLStreamReader
        private var event = XmlPullParser.START_DOCUMENT
        private var level = 0
        private var popPending = false
        private var currentText: String? = null

        override fun setInput(inputStream: InputStream?, inputEncoding: String?) {
            reader = XMLInputFactory.newInstance().apply {
                setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
                setProperty(XMLInputFactory.SUPPORT_DTD, false)
                setProperty(XMLInputFactory.IS_COALESCING, true)
            }.createXMLStreamReader(inputStream)
        }

        override fun next(): Int {
            if (popPending) {
                level--
                popPending = false
            }
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        level++
                        return XmlPullParser.START_TAG.also { event = it }
                    }
                    XMLStreamConstants.END_ELEMENT -> {
                        popPending = true
                        return XmlPullParser.END_TAG.also { event = it }
                    }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                        currentText = reader.text
                        return XmlPullParser.TEXT.also { event = it }
                    }
                    XMLStreamConstants.END_DOCUMENT -> return XmlPullParser.END_DOCUMENT.also { event = it }
                }
            }
            return XmlPullParser.END_DOCUMENT.also { event = it }
        }

        override fun getEventType(): Int = event
        override fun getDepth(): Int = level
        override fun getName(): String? = reader.localName
        override fun getNamespace(): String = reader.namespaceURI.orEmpty()
        override fun getText(): String? = currentText
        override fun getAttributeCount(): Int = if (event == XmlPullParser.START_TAG) reader.attributeCount else -1
        override fun getAttributeName(index: Int): String = reader.getAttributeLocalName(index)
        override fun getAttributeNamespace(index: Int): String = reader.getAttributeNamespace(index).orEmpty()
        override fun getAttributeValue(index: Int): String = reader.getAttributeValue(index)
        override fun getAttributeValue(namespace: String?, name: String?): String? =
            reader.getAttributeValue(namespace, name)
        override fun setFeature(name: String?, state: Boolean) = Unit
        override fun getFeature(name: String?): Boolean = false

        override fun setProperty(name: String?, value: Any?) = unsupported()
        override fun getProperty(name: String?): Any = unsupported()
        override fun setInput(input: Reader?) = unsupported()
        override fun getInputEncoding(): String = unsupported()
        override fun defineEntityReplacementText(entityName: String?, replacementText: String?) = unsupported()
        override fun getNamespaceCount(depth: Int): Int = unsupported()
        override fun getNamespacePrefix(pos: Int): String = unsupported()
        override fun getNamespaceUri(pos: Int): String = unsupported()
        override fun getNamespace(prefix: String?): String = unsupported()
        override fun getPositionDescription(): String = "line ${reader.location.lineNumber}"
        override fun getLineNumber(): Int = reader.location.lineNumber
        override fun getColumnNumber(): Int = reader.location.columnNumber
        override fun isWhitespace(): Boolean = currentText.isNullOrBlank()
        override fun getTextCharacters(holderForStartAndLength: IntArray?): CharArray = unsupported()
        override fun getPrefix(): String = unsupported()
        override fun isEmptyElementTag(): Boolean = unsupported()
        override fun getAttributePrefix(index: Int): String = unsupported()
        override fun getAttributeType(index: Int): String = unsupported()
        override fun isAttributeDefault(index: Int): Boolean = unsupported()
        override fun nextToken(): Int = unsupported()
        override fun require(type: Int, namespace: String?, name: String?) = unsupported()
        override fun nextText(): String = unsupported()
        override fun nextTag(): Int = unsupported()

        private fun unsupported(): Nothing = throw UnsupportedOperationException("not used by the reader")
    }

    private companion object {
        const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        const val PKG_RELS = "http://schemas.openxmlformats.org/package/2006/relationships"
        const val OFFICE_RELS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        const val REL_STYLES = "$OFFICE_RELS/styles"
        const val REL_NUMBERING = "$OFFICE_RELS/numbering"
        const val REL_IMAGE = "$OFFICE_RELS/image"
        const val PICTURE_URI = "http://schemas.openxmlformats.org/drawingml/2006/picture"
        const val CHART_URI = "http://schemas.openxmlformats.org/drawingml/2006/chart"
        const val SHAPE_URI = "http://schemas.microsoft.com/office/word/2010/wordprocessingShape"

        const val NAMESPACES = """xmlns:w="$W" xmlns:r="$OFFICE_RELS"
            xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
            xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
            xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart"
            xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
            xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"
            xmlns:v="urn:schemas-microsoft-com:vml"
            xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math""""

        const val CONTENT_TYPES = """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>"""

        const val ROOT_RELS = """<Relationships xmlns="$PKG_RELS">
            <Relationship Id="rId1" Type="$OFFICE_RELS/officeDocument" Target="word/document.xml"/>
            </Relationships>"""

        const val STYLES = """<w:styles $NAMESPACES>
            <w:docDefaults>
              <w:rPrDefault><w:rPr>
                <w:rFonts w:ascii="Calibri" w:hAnsi="Calibri"/><w:sz w:val="22"/>
              </w:rPr></w:rPrDefault>
              <w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="259" w:lineRule="auto"/></w:pPr></w:pPrDefault>
            </w:docDefaults>
            <w:style w:type="paragraph" w:default="1" w:styleId="Normal"/>
            <w:style w:type="paragraph" w:styleId="Heading1">
              <w:basedOn w:val="Normal"/>
              <w:pPr><w:spacing w:before="240" w:after="0"/></w:pPr>
              <w:rPr><w:b/><w:sz w:val="32"/></w:rPr>
            </w:style>
            <w:style w:type="table" w:default="1" w:styleId="TableNormal"/>
            <w:style w:type="table" w:styleId="TableGrid">
              <w:basedOn w:val="TableNormal"/>
              <w:pPr><w:spacing w:after="0" w:line="240" w:lineRule="auto"/></w:pPr>
              <w:tblPr><w:tblBorders>
                <w:top w:val="single" w:sz="4"/><w:left w:val="single" w:sz="4"/>
                <w:bottom w:val="single" w:sz="4"/><w:right w:val="single" w:sz="4"/>
              </w:tblBorders></w:tblPr>
            </w:style>
            </w:styles>"""

        const val NUMBERING = """<w:numbering $NAMESPACES>
            <w:abstractNum w:abstractNumId="0">
              <w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/>
                <w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr></w:lvl>
              <w:lvl w:ilvl="1"><w:start w:val="1"/><w:numFmt w:val="lowerLetter"/><w:lvlText w:val="%2)"/>
                <w:pPr><w:ind w:left="1440" w:hanging="360"/></w:pPr></w:lvl>
            </w:abstractNum>
            <w:abstractNum w:abstractNumId="1">
              <w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="&#xF0B7;"/>
                <w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr>
                <w:rPr><w:rFonts w:ascii="Symbol" w:hAnsi="Symbol"/></w:rPr></w:lvl>
            </w:abstractNum>
            <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
            <w:num w:numId="2"><w:abstractNumId w:val="1"/></w:num>
            </w:numbering>"""

        const val TABLE = """<w:tbl>
            <w:tblPr><w:tblStyle w:val="TableGrid"/></w:tblPr>
            <w:tblGrid><w:gridCol w:w="2000"/><w:gridCol w:w="2000"/><w:gridCol w:w="2000"/></w:tblGrid>
            <w:tr>
              <w:tc>
                <w:tcPr><w:gridSpan w:val="2"/><w:shd w:val="clear" w:fill="D9E2F3"/></w:tcPr>
                <w:p><w:r><w:t>Head</w:t></w:r></w:p>
              </w:tc>
              <w:tc><w:p><w:r><w:t>C1</w:t></w:r></w:p></w:tc>
            </w:tr>
            <w:tr>
              <w:tc><w:tcPr><w:vMerge w:val="restart"/></w:tcPr><w:p><w:r><w:t>A2</w:t></w:r></w:p></w:tc>
              <w:tc><w:p><w:r><w:t>B2</w:t></w:r></w:p></w:tc>
              <w:tc><w:p><w:r><w:t>C2</w:t></w:r></w:p></w:tc>
            </w:tr>
            <w:tr>
              <w:tc><w:tcPr><w:vMerge/></w:tcPr><w:p/></w:tc>
              <w:tc><w:p><w:r><w:t>B3</w:t></w:r></w:p></w:tc>
              <w:tc><w:p><w:r><w:t>C3</w:t></w:r></w:p></w:tc>
            </w:tr>
            </w:tbl>"""
    }
}
