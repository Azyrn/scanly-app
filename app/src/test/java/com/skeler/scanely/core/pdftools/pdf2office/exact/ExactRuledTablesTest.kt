package com.skeler.scanely.core.pdftools.pdf2office.exact

import com.skeler.scanely.core.pdfeditor.engine.PdfiumChar
import com.skeler.scanely.core.pdfeditor.engine.PdfiumPage
import com.skeler.scanely.core.pdfeditor.engine.PdfiumRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tables from ruling lines ([ExactRules], [ExactRuledTables], [ExactTableGrid]) on pages shaped like
 * the UN budget tables Word exports (un_en_budget_29a pp. 46 and 48): rules only across, one rule
 * segment per cell, a two-level header, and labels that wrap with their figures on the last line.
 */
class ExactRuledTablesTest {

    /** Column edges: a label column and three figure columns. */
    private val edges = listOf(72f, 252f, 352f, 452f, 552f)

    @Test
    fun columnsComeFromTheEndsOfRuleSegmentsWhenThereAreNoVerticalRules() {
        val chars = row(705f, "Category", "2023", "2024", "2025") +
            row(680f, "Posts", "12", "15", "18") +
            row(666f, "Travel", "3", "4", "5") +
            row(652f, "Supplies", "70", "81", "90")
        val rules = across(720f) + across(695f) + across(640f)

        val table = analyze(chars, rules).tables.single()

        assertEquals(4, table.columns)
        assertEquals(
            listOf(
                listOf("Category", "2023", "2024", "2025"),
                listOf("Posts", "12", "15", "18"),
                listOf("Travel", "3", "4", "5"),
                listOf("Supplies", "70", "81", "90")
            ),
            texts(table)
        )
    }

    @Test
    fun aHeaderWrappedBetweenTwoRulesIsOneRowWithTwoLineCells() {
        val chars = row(712f, "Item", "2024", "2025", "Posts") +
            words(edges[2] + 4f, 702f, "estimate") +
            row(680f, "General", "10", "11", "4") +
            row(666f, "Support", "20", "21", "5")
        val rules = across(722f) + across(695f) + across(655f)

        val table = analyze(chars, rules).tables.single()

        val header = table.rows.first().cells
        assertEquals("2025 estimate", header[2].text)
        assertEquals(2, header[2].lines.size)
        assertEquals(3, table.rows.size)
    }

    @Test
    fun aLabelWrappedOverSeveralLinesWithFiguresOnTheLastIsOneRow() {
        val chars = row(705f, "Component", "2023", "2024", "2025") +
            row(682f, "Executive direction", "5", "6", "7") +
            words(edges[0] + 4f, 668f, "Programme of work and") +
            words(edges[0] + 4f, 659f, "support for the regional") +
            row(650f, "commissions", "40", "42", "44") +
            row(636f, "Total", "45", "48", "51")
        val rules = across(720f) + across(695f) + across(625f)

        val table = analyze(chars, rules).tables.single()

        assertEquals(4, table.rows.size)
        val wrapped = table.rows[2].cells
        assertEquals("Programme of work and support for the regional commissions", wrapped[0].text)
        assertEquals(3, wrapped[0].lines.size)
        assertEquals("44", wrapped[3].text)
    }

    @Test
    fun aCellOverSeveralColumnsSpansThemAndACellOverTwoBandsMergesDown() {
        val chars = words(edges[2] - 20f, 712f, "Resources") +
            row(699f, "Unit", "Posts", "2024", "2025") +
            row(680f, "Office", "3", "10", "11") +
            row(666f, "Field", "4", "12", "13")
        // A fully ruled grid, borders cell by cell: "Resources" spans the three figure columns, so the
        // rules inside it start below it; "Unit" spans both header rows, so no rule runs under its top half.
        val rules = listOf(rule(edges[0], edges[1], 722f), rule(edges[1], edges[4], 722f)) +
            edges.drop(1).zipWithNext { a, b -> rule(a, b, 707f) } +
            across(695f) + across(655f) +
            listOf(edges[0], edges[1], edges[4]).map { vertical(it, 655f, 722f) } +
            listOf(edges[2], edges[3]).map { vertical(it, 655f, 707f) }

        val table = analyze(chars, rules).tables.single()

        val top = table.rows.first().cells
        assertEquals(listOf(1, 3), top.map { it.span })
        assertEquals("Resources", top[1].text)
        assertEquals(CellMerge.RESTART, top[0].merge)
        assertEquals("Unit", top[0].text)
        assertEquals(CellMerge.CONTINUE, table.rows[1].cells[0].merge)
        assertEquals(listOf("", "Posts", "2024", "2025"), table.rows[1].cells.map { it.text })
    }

    @Test
    fun aGridOfLabelledBoxesAcrossItIsAFormAndStaysText() {
        // W-9 style: rows that are one box across the grid, over a row of digit boxes.
        val chars = words(76f, 712f, "Name of entity") + words(76f, 697f, "Business name") +
            words(76f, 682f, "Address") + words(76f, 667f, "City state and ZIP code") +
            row(652f, "Number", "1", "2", "3")
        val rules = listOf(722f, 707f, 692f, 677f).map { rule(edges[0], edges[4], it) } +
            across(662f) + across(647f) +
            listOf(edges[0], edges[4]).map { vertical(it, 647f, 722f) } +
            edges.subList(1, 4).map { vertical(it, 647f, 662f) }

        assertTrue(analyze(chars, rules).tables.isEmpty())
    }

    @Test
    fun aTableAtThePageFootContinuesWhereTheNextPageRepeatsItsHeaderOnTheSameColumns() {
        val rules = across(240f) + across(225f) + across(60f)
        val first = analyze(row(230f, "Category", "2023", "2024", "2025") + body(210f, 9), rules)
        val next = analyze(
            row(740f, "Category", "2023", "2024", "2025") + body(720f, 4, from = 9),
            across(750f) + across(735f) + across(660f)
        )
        val elsewhere = analyze(
            row(740f, "Category", "2023", "2024", "2025") + body(720f, 4, from = 9),
            listOf(72f, 200f, 552f).zipWithNext { a, b -> rule(a, b, 750f) } +
                listOf(72f, 200f, 552f).zipWithNext { a, b -> rule(a, b, 660f) }
        )

        val open = ExactTablePages.open(first)!!
        val piece = ExactTablePages.continuation(open, next)!!
        assertEquals(1, ExactTablePages.repeatedRows(open, piece))
        assertEquals(null, ExactTablePages.continuation(open, elsewhere))
    }

    @Test
    fun aTableOverTwoPagesIsWrittenAsOneTableInTheBodyWithItsHeaderRowRepeated() {
        val rules = across(240f) + across(225f) + across(60f)
        val first = analyze(row(230f, "Category", "2023", "2024", "2025") + body(210f, 9), rules)
        val next = analyze(
            row(740f, "Category", "2023", "2024", "2025") + body(720f, 4, from = 9) + words(300f, 40f, "Page 2"),
            across(750f) + across(735f) + across(660f)
        )
        val out = java.io.ByteArrayOutputStream()
        val writer = ExactDocxWriter(out, TextMeasurer { text, _, _, _, _, size -> text.length * size / 2f })
        writer.addPage(612f, 792f, first, null)
        writer.addPage(612f, 792f, next, null)
        writer.finish()

        val document = java.util.zip.ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.first { it.name == "word/document.xml" }
            zip.readBytes().decodeToString()
        }
        val body = document.substringAfter("<w:body>")
        assertEquals(1, Regex("<w:tbl>").findAll(body).count())
        assertEquals(1, Regex("<w:tblHeader/>").findAll(body).count())
        // 1 header + 9 rows, then 4 rows: the repeated header is not written again.
        assertEquals(14, Regex("<w:tr>").findAll(body).count())
        assertEquals(1, Regex("<w:sectPr>").findAll(body).count())
        // Page 2's page number is anchored in its first row and placed on the page.
        val row = body.split("<w:tr>")[11]
        assertTrue(row.contains("Page") && row.contains("layoutInCell=\"0\""))
    }

    @Test
    fun aTableOverTwoPagesIsOneTableOnTheSheetWhereItStarts() {
        val rules = across(240f) + across(225f) + across(60f)
        val first = analyze(row(230f, "Category", "2023", "2024", "2025") + body(210f, 9), rules)
        val next = analyze(
            row(740f, "Category", "2023", "2024", "2025") + body(720f, 4, from = 9) + words(300f, 40f, "Page 2"),
            across(750f) + across(735f) + across(660f)
        )
        val out = java.io.ByteArrayOutputStream()
        val writer = com.skeler.scanely.core.pdftools.pdf2office.XlsxWriter(out)
        val sheets = ExactSheets(writer)
        sheets.add("Page 1", first)
        sheets.add("Page 2", next)
        sheets.finish()
        writer.finish("Text")

        val parts = HashMap<String, String>()
        java.util.zip.ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { parts[it.name] = zip.readBytes().decodeToString() }
        }
        // 1 header row + 9 + 4: the repeated header is dropped; page 2 keeps only its page number.
        assertEquals(14, Regex("<row ").findAll(parts.getValue("xl/worksheets/sheet1.xml")).count())
        assertEquals(1, Regex("<row ").findAll(parts.getValue("xl/worksheets/sheet2.xml")).count())
    }

    @Test
    fun proseBetweenRulesIsNotATable() {
        val chars = words(72f, 700f, "A paragraph of running text") + words(72f, 686f, "that wraps onto a line")

        assertTrue(analyze(chars, listOf(rule(72f, 552f, 712f), rule(72f, 552f, 676f))).tables.isEmpty())
    }

    private fun analyze(chars: List<PdfiumChar>, rules: List<PdfiumRule>): ExactPageLayout {
        val page = PdfiumPage(612f, 792f, chars, rules)
        return ExactTables.detect(ExactLayout.analyze(page), rules = page.rules)
    }

    private fun texts(table: ExactTable): List<List<String>> = table.rows.map { row -> row.cells.map { it.text } }

    /** One table line: the label left in the first column, figures right-aligned in the others. */
    private fun row(baseline: Float, label: String, vararg figures: String): List<PdfiumChar> =
        words(edges[0] + 4f, baseline, label) + figures.flatMapIndexed { i, text ->
            words(edges[i + 2] - 4f - text.length * ADVANCE, baseline, text)
        }

    /** [count] table lines of figures, 14 pt apart from [top] down. */
    private fun body(top: Float, count: Int, from: Int = 0): List<PdfiumChar> =
        (from until from + count).flatMap { i ->
            row(top - (i - from) * 14f, "Item$i", "${i + 10}", "${i + 20}", "${i + 30}")
        }

    /** A horizontal rule at [y], drawn cell by cell as Word draws borders. */
    private fun across(y: Float): List<PdfiumRule> = edges.zipWithNext { a, b -> rule(a, b, y) }

    private fun rule(from: Float, to: Float, y: Float) = PdfiumRule(from, y - 0.25f, to, y + 0.25f)

    private fun vertical(x: Float, from: Float, to: Float) = PdfiumRule(x - 0.25f, from, x + 0.25f, to)

    /** Characters of a line of text, spaces included (Word draws its spaces). */
    private fun words(x: Float, baseline: Float, text: String): List<PdfiumChar> {
        val out = ArrayList<PdfiumChar>()
        text.forEachIndexed { i, c ->
            val at = x + i * ADVANCE
            out += PdfiumChar(
                code = c.code, originX = at, originY = baseline,
                left = at, bottom = baseline - 2f, right = at + ADVANCE, top = baseline + 7f,
                size = SIZE, matrixA = 1f, matrixB = 0f, matrixC = 0f, matrixD = 1f,
                font = "TimesNewRomanPSMT", fontFlags = 0, weight = 400, argb = 0xFF000000.toInt(),
                flags = 0, renderMode = 0
            )
        }
        return out
    }

    private companion object {
        const val SIZE = 9f
        const val ADVANCE = 4.5f
    }
}
