package com.skeler.scanely.core.pdftools.pdf2office

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Finds runs of lines that form a table, and lays their cells into a grid. Which runs are tables is
 * [LayoutTableGates]' call (borderless-table rules; there are no vector lines here, so charts and
 * chess patterns aren't told apart from tables by their drawing).
 */
internal object LayoutTables {

    /**
     * Tables found on one page. [unconfirmed]: a run passed every gate but reads like the ends of text
     * columns, and no layout-model table regions were given to settle it — the caller may look at the
     * page with the model and ask again (see [findTables]).
     */
    class Found(val ranges: List<IntRange>, val unconfirmed: Boolean)

    /**
     * Runs of consecutive lines whose cells line up — by left edge, right edge (numbers are usually
     * right-aligned) or centre — with at least two shared columns, plus the single-cell rows a table
     * holds (a section label, a total). A failed run is skipped as a whole, which keeps the scan linear.
     *
     * A run whose cells end sentences or break words is kept only inside one of [modelTables] (table
     * regions from the layout model, in the lines' coordinates); null means the model wasn't asked.
     */
    fun findTables(lines: List<LayoutLine>, modelTables: List<LayoutBox>? = null): Found {
        val tables = mutableListOf<IntRange>()
        var unconfirmed = false
        var index = 0
        while (index < lines.size) {
            val end = if (lines[index].cells.size < 2) index else tableEnd(lines, index)
            val run = lines.subList(index, end + 1)
            if (end > index && LayoutTableGates.looksTabular(run)) {
                when {
                    !LayoutTableGates.endsTextColumns(run) -> tables += index..end
                    modelTables == null -> unconfirmed = true
                    modelTables.any { covers(it, box(run)) } -> tables += index..end
                }
            }
            index = end + 1
        }
        return Found(tables, unconfirmed)
    }

    /**
     * The last line of the aligned run starting at [start]. A single-cell row only stays when a full
     * row follows it, or when it is a numeric total at the end; otherwise the run stops before it, so
     * the paragraph under a table isn't swallowed.
     */
    private fun tableEnd(lines: List<LayoutLine>, start: Int): Int {
        val columns = lines[start].cells.map { Column(it.box.left, it.box.right) }.toMutableList()
        var end = start
        var next = start + 1
        while (next < lines.size && next - start < MAX_TABLE_ROWS && carriesRun(lines, next, columns)) {
            if (lines[next].cells.size >= 2) end = next
            next++
        }
        while (end + 1 < next && isNumeric(lines[end + 1].cells.single().text)) end++
        return end
    }

    /** Line [next] is close below the one before and extends the run's columns, or is a section row. */
    private fun carriesRun(lines: List<LayoutLine>, next: Int, columns: MutableList<Column>): Boolean {
        val line = lines[next]
        if (line.box.top - lines[next - 1].box.bottom > line.fontSize * ROW_GAP_EM) return false
        return if (line.cells.size >= 2) extends(line, columns) else isSectionRow(line, columns)
    }

    /** Whether multi-cell [next] continues the run; its new columns join [columns] if so. */
    private fun extends(next: LayoutLine, columns: MutableList<Column>): Boolean {
        val tolerance = tolerance(next)
        val unmatched = mutableListOf<LayoutCell>()
        for (cell in next.cells) {
            val column = columns.firstOrNull { aligned(it, cell.box, tolerance) }
            if (column == null) unmatched += cell else column.width = max(column.width, cell.box.width)
        }
        val fits = next.cells.size - unmatched.size >= 2 && columns.size + unmatched.size <= MAX_TABLE_COLUMNS
        if (fits) unmatched.forEach { columns += Column(it.box.left, it.box.right) }
        return fits
    }

    /** A short single cell that lines up with exactly one column and is about as wide as it. */
    private fun isSectionRow(line: LayoutLine, columns: List<Column>): Boolean {
        val cell = line.cells.single()
        if (cell.wordCount > MAX_SECTION_WORDS) return false
        val tolerance = tolerance(line)
        val column = columns.singleOrNull { aligned(it, cell.box, tolerance) } ?: return false
        return cell.box.width <= max(column.width * SECTION_WIDTH_FACTOR, column.width + tolerance)
    }

    private fun tolerance(line: LayoutLine): Float = max(MIN_ALIGN_TOLERANCE_PT, line.fontSize * ALIGN_TOLERANCE_EM)

    private fun aligned(column: Column, box: LayoutBox, tolerance: Float): Boolean =
        abs(column.left - box.left) <= tolerance ||
            abs(column.right - box.right) <= tolerance ||
            abs((column.left + column.right) / 2f - box.centerX) <= tolerance

    /**
     * The numeric cell test the gates need: digits with only separators,
     * signs, brackets and currency marks around them. "1,234.50", "(1,200)", "-3.5%", "$99" and
     * "12/31/2024" are numbers; "12 kg", "Q1" and "N/A" are not.
     */
    fun isNumeric(text: String): Boolean =
        text.any(Char::isDigit) && text.all { char ->
            char.isDigit() || char.isWhitespace() || char in NUMERIC_MARKS ||
                Character.getType(char) == Character.CURRENCY_SYMBOL.toInt()
        }

    fun buildTable(rows: List<LayoutLine>): TableBlock {
        val grid = grid(rows)
        val box = rows.map { it.box }.reduce { a, b -> a.union(b) }
        val columns = grid.columns
        return TableBlock(
            rows = grid.slots.map { row -> row.map { it.text() } },
            rowBoxes = rows.map { it.box },
            box = box,
            fontSize = rows.map { it.fontSize }.sorted()[rows.size / 2],
            columnWidthsPt = columns.mapIndexed { i, column ->
                (
                    columns.getOrNull(
                        i + 1
                    )?.left ?: box.right
                    ) - column.left
            }
        )
    }

    private const val ROW_GAP_EM = 2.5f
    private const val MIN_ALIGN_TOLERANCE_PT = 3f
    private const val ALIGN_TOLERANCE_EM = 0.6f
    private const val MAX_TABLE_ROWS = 2_000
    private const val MAX_TABLE_COLUMNS = 40
    private const val NUMERIC_MARKS = "+-.,:%/()"

    // Section-label rows.
    private const val MAX_SECTION_WORDS = 4
    private const val SECTION_WIDTH_FACTOR = 1.5f
}

private fun box(rows: List<LayoutLine>): LayoutBox = rows.map { it.box }.reduce(LayoutBox::union)

/** Whether [region] holds at least [MODEL_COVER] of [box]'s area. */
private fun covers(region: LayoutBox, box: LayoutBox): Boolean {
    val width = min(region.right, box.right) - max(region.left, box.left)
    val height = min(region.bottom, box.bottom) - max(region.top, box.top)
    if (width <= 0f || height <= 0f) return false
    return width * height >= box.width * box.height * MODEL_COVER
}

private const val MODEL_COVER = 0.5f
