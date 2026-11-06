package com.skeler.scanely.core.pdftools.pdf2office.exact

import com.skeler.scanely.core.pdftools.pdf2office.LayoutBox
import com.skeler.scanely.core.pdftools.pdf2office.PptxWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactReadingOrderTest {
    @Test
    fun tablePrecedesFollowingTextInItsOwnColumnWithoutReorderingOtherBlocks() {
        val above = block("above", 20f, 20f)
        val side = block("side", 300f, 120f)
        val below = block("below", 20f, 120f)
        val table = table(50f)
        val layout = ExactPageLayout(600f, 800f, listOf(above, side, below), tables = listOf(table))
        val ordered = mutableListOf<Any>()

        layout.forEachInReadingOrder(ordered::add, ordered::add)

        assertEquals(listOf(above, side, table, below), ordered)
        assertSame("ordering must not rebuild or move the table", table.box, (ordered[2] as ExactTable).box)
        assertSame("ordering must not rebuild or move the text", below.lines, (ordered[3] as Block).lines)
    }

    @Test
    fun tablesSharingAnInsertionPointAreTopToBottomAndTerminalTablesAreKept() {
        val below = block("below", 20f, 200f)
        val first = table(50f)
        val second = table(100f)
        val terminal = table(300f)
        val layout = ExactPageLayout(600f, 800f, listOf(below), tables = listOf(terminal, second, first))
        val ordered = mutableListOf<Any>()

        layout.forEachInReadingOrder(ordered::add, ordered::add)

        assertEquals(listOf(first, second, below, terminal), ordered)
    }

    @Test
    fun aPageWithoutProseStillWritesEveryTable() {
        val table = table(50f)
        val layout = ExactPageLayout(600f, 800f, emptyList(), tables = listOf(table))
        val ordered = mutableListOf<Any>()

        layout.forEachInReadingOrder(ordered::add, ordered::add)

        assertEquals(listOf(table), ordered)
    }

    @Test
    fun bothWritersSerializeTheTableBeforeFollowingProse() {
        val layout = ExactPageLayout(
            600f, 800f, listOf(block("above", 20f, 20f), block("below", 20f, 120f)), tables = listOf(table(50f))
        )
        val measurer = TextMeasurer { text, _, _, _, _, size -> text.length * size / 2f }
        val word = ByteArrayOutputStream().also { out ->
            ExactDocxWriter(out, measurer).apply {
                addPage(600f, 800f, layout, null)
                finish()
            }
        }
        val slides = ByteArrayOutputStream().also { out ->
            PptxWriter(out, 600f, 800f).apply {
                addExactSlide(layout, 600f, 800f, null, emptyList(), measurer)
                finish()
            }
        }
        listOf(part(word, "word/document.xml"), part(slides, "ppt/slides/slide1.xml")).forEach { xml ->
            assertTrue(xml.indexOf(">above<") < xml.indexOf(">table<"))
            assertTrue(xml.indexOf(">table<") < xml.indexOf(">below<"))
            listOf("above", "table", "below").forEach { text ->
                assertEquals(1, Regex(">$text<").findAll(xml).count())
            }
        }
    }

    private fun part(bytes: ByteArrayOutputStream, name: String): String =
        ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip ->
            checkNotNull(generateSequence { zip.nextEntry }.firstOrNull { it.name == name }) { "Missing $name" }
            zip.readBytes().decodeToString()
        }

    private fun block(text: String, left: Float, top: Float): Block {
        val style = TextStyle(OfficeFamily.ARIAL, false, false, false, 10f, 0)
        val token = Token(text, style, left, left + 100f, false, 0f)
        return Block(listOf(Segment(listOf(token), 800f - top - 9.6f, 10f, false, 0)), 12f, 0f)
    }

    private fun table(top: Float): ExactTable {
        val box = LayoutBox(20f, top, 120f, top + 20f)
        val cell = ExactTableCell(block("table", 20f, top).lines)
        return ExactTable(listOf(ExactTableRow(listOf(cell), box, 10f)), listOf(100f), box, 10f)
    }
}
