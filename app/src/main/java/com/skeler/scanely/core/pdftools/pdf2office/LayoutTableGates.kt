package com.skeler.scanely.core.pdftools.pdf2office

import com.skeler.scanely.core.pdftools.pdf2office.LayoutTables.isNumeric
import kotlin.math.abs

/**
 * The acceptance gates of [LayoutTables]: borderless-table rules (a grid score, then fill, size and
 * pitch checks) for a text-only model, thresholds measured on the fidelity corpus, and the tests
 * that send a run to the layout model.
 */
internal object LayoutTableGates {

    /** The acceptance rule, after the tests that tell text in columns from a table. */
    fun looksTabular(rows: List<LayoutLine>): Boolean {
        val grid = grid(rows)
        val filled = grid.slots.sumOf { row -> row.count { it.isNotEmpty() } }
        if (rows.size < 2 || grid.columns.size < 2 || filled < 2) return false
        val numeric = grid.slots.sumOf { row -> row.count { slot -> slot.isNotEmpty() && isNumeric(slot.text()) } }
            .toFloat() / filled
        if (readsAsText(rows.flatMap { it.cells }, numeric)) return false
        // Columns and paragraphs come before tables: a column of wrapped text in the run is a text
        // column beside something else, not a table column.
        if (grid.columns.indices.any { isProseColumn(grid, it) }) return false
        return gridAccepts(rows, grid, filled.toFloat() / (rows.size * grid.columns.size), numeric)
    }

    /**
     * Two-column prose also lines up in two "cells" per line; what gives it away is long cells with
     * few numbers. A bullet in every cell is a list set in columns (irs_p17's cover link list), and
     * entries ending in page numbers ("Seminars 25", "Taxability of 62, 63") an index.
     */
    private fun readsAsText(raw: List<LayoutCell>, numeric: Float): Boolean {
        val wordsPerCell = raw.sumOf { it.wordCount }.toFloat() / raw.size
        return when {
            wordsPerCell > MAX_WORDS_PER_CELL && numeric < PROSE_NUMERIC -> true
            raw.all { it.text.trimStart().firstOrNull() in ParagraphText.BULLET_GLYPHS } -> true
            else -> numeric < PROSE_NUMERIC && raw.count { INDEX_ENTRY.matches(it.text) } >= raw.size * INDEX_SHARE
        }
    }

    private fun gridAccepts(rows: List<LayoutLine>, grid: Grid, fill: Float, numeric: Float): Boolean {
        val cols = grid.columns.size
        if (gridScore(grid) > REGULAR_GRID_SCORE) return true
        if (tooSparse(cols, rows.size * cols, fill)) return false
        if (tightFigures(rows.size, cols, fill, numeric)) return true
        if (rows.size < FEW_ROWS && pitchOutliers(rows) > 1) return false
        return fill >= MIN_FILL && (cols >= WIDE_COLUMNS || rows.size >= MIN_ROWS)
    }

    /**
     * Rows that read as text set in columns: index entries, or a few rows with cells that mostly end
     * sentences or break words (the ends of text columns sharing baselines by chance: 1000_0.pdf,
     * irs_p17 p54, captions side by side), or every column a wrapped phrase (1000_0's headline strip).
     * Real tables of words do that too ("$246,000 or more | no deduction."), which is why the layout
     * model decides.
     */
    fun endsTextColumns(rows: List<LayoutLine>): Boolean {
        val cells = rows.flatMap { it.cells }
        // Part of an index, cut into cells differently than the full-index gate expects (form
        // numbers beside page numbers, "Address 16 | 4506 17 | W-4V:"): the model sees no table there.
        if (cells.count { INDEX_ENTRY.matches(it.text) } >= cells.size * INDEX_DOUBT_SHARE) return true
        if (rows.size >= FEW_ROWS || cells.count { isNumeric(it.text) } >= cells.size * PROSE_NUMERIC) return false
        return carriesSentences(rows, cells) || everyColumnAPhrase(grid(rows))
    }

    /**
     * Cells that mostly end sentences or break words, or rows below the first that mostly start in
     * lower case ("de Fès | sa", "compte | du"; IF/THEN tables do the same, so this is only a doubt).
     */
    private fun carriesSentences(rows: List<LayoutLine>, cells: List<LayoutCell>): Boolean {
        val ends = cells.count { it.text.trimEnd().lastOrNull() in SENTENCE_ENDS }
        if (ends >= cells.size * SENTENCE_END_SHARE) return true
        val below = rows.drop(1).flatMap { it.cells }.filter { it.text.isNotBlank() }
        val carried = below.count { cell -> cell.text.first().isLowerCase() && cell.text.none(Char::isDigit) }
        return below.isNotEmpty() && carried >= below.size * LOWER_START_SHARE
    }

    /** A phrase starts in capitals and carries on in lower case; a column of lower-case values doesn't. */
    private fun everyColumnAPhrase(grid: Grid): Boolean {
        val columns = grid.columns.indices
            .map { column -> grid.slots.mapNotNull { row -> row[column].takeIf { it.isNotEmpty() }?.text() } }
            .filter { it.size > 1 }
        return columns.isNotEmpty() && columns.all { texts ->
            !texts.first().first().isLowerCase() && texts.zipWithNext().all { (line, next) -> carriesOn(line, next) }
        }
    }

    /**
     * Lines of a wrapped paragraph: several words each, most of them filling the column's width
     * (justified or nearly so), and either visibly wrapped — a word broken at the line end, a line
     * that carries on a sentence in lower case — or long (which needs no letter case, for Arabic).
     * Table cells are short and ragged; a table cell that wraps is the exception, not most of a column.
     */
    private fun isProseColumn(grid: Grid, column: Int): Boolean {
        val cells = grid.slots.mapNotNull { row -> row[column].takeIf { it.isNotEmpty() } }
        // A row of figures read as one long cell is wide and "wordy" too, but never prose.
        if (cells.size < MIN_PROSE_LINES || cells.count { isNumeric(it.text()) } * 2 >= cells.size) return false
        val words = cells.sumOf { slot -> slot.sumOf { it.wordCount } }.toFloat() / cells.size
        if (words < MIN_PROSE_WORDS) return false
        val widths = cells.map { slot -> slot.maxOf { it.box.right } - slot.minOf { it.box.left } }
        val widest = widths.max()
        if (widths.count { it >= widest * FULL_LINE } < cells.size * FULL_LINE_SHARE) return false
        val wrapped = cells.map { it.text() }.zipWithNext(::carriesOn)
        return words >= LONG_LINE_WORDS || wrapped.count { it } >= wrapped.size * WRAPPED_SHARE
    }

    /** Product over columns of the share of rows with a cell on that column's left or right edge. */
    private fun gridScore(grid: Grid): Float {
        var score = 1f
        grid.columns.forEachIndexed { column, edges ->
            val alignedRows = grid.slots.count { row ->
                row[column].any {
                    abs(
                        it.box.left - edges.left
                    ) <= GRID_EDGE_PT || abs(it.box.right - edges.right) <= GRID_EDGE_PT
                }
            }
            score *= alignedRows.toFloat() / grid.slots.size
        }
        return score
    }

    /** Rows set much further apart than rows are tall (a stand-in for a layout check). */
    private fun pitchOutliers(rows: List<LayoutLine>): Int {
        val height = rows.map { it.box.height }.average().toFloat()
        return rows.zipWithNext { a, b -> b.box.centerY - a.box.centerY }.count { it > height * OUTLIER_PITCH }
    }

    private const val MAX_WORDS_PER_CELL = 5f

    // Wrapped-text columns (measured on the fidelity corpus and 1000_0.pdf; see isProseColumn).
    private const val MIN_PROSE_LINES = 2
    private const val MIN_PROSE_WORDS = 3f
    private const val LONG_LINE_WORDS = 5f
    private const val FULL_LINE = 0.8f
    private const val FULL_LINE_SHARE = 0.6f
    private const val WRAPPED_SHARE = 0.3f

    // Index columns (irs_p17 pp. 136-142).
    private val INDEX_ENTRY = Regex(""".*\p{L}.*\s\d{1,4}(?:[,–-]\s?\d{1,4})*""")
    private const val INDEX_SHARE = 0.5f
    private const val INDEX_DOUBT_SHARE = 0.3f

    // Column ends (see endsTextColumns).
    private val SENTENCE_ENDS = setOf('.', '!', '?', '-', '­', '‐')
    private const val SENTENCE_END_SHARE = 0.5f
    private const val LOWER_START_SHARE = 0.5f

    // Acceptance gates (thresholds measured on the fidelity corpus).
    private const val PROSE_NUMERIC = 0.2f
    private const val GRID_EDGE_PT = 1f
    private const val REGULAR_GRID_SCORE = 0.9f
    private const val FEW_ROWS = 7
    private const val OUTLIER_PITCH = 2.5f
    private const val MIN_FILL = 0.5f
    private const val MIN_ROWS = 3
}

/** The small-table fill gate: fewer than four columns and under 100 / (cells + 148) filled. */
private fun tooSparse(cols: Int, cells: Int, fill: Float): Boolean =
    cols < WIDE_COLUMNS && fill < SPARSE_FILL_NUMERATOR / (cells + SPARSE_FILL_OFFSET)

/** Tight rows of figures, every row populated (a run here never holds an empty row). */
private fun tightFigures(rows: Int, cols: Int, fill: Float, numeric: Float): Boolean =
    rows >= TIGHT_ROWS && cols >= WIDE_COLUMNS && fill > TIGHT_FILL && numeric >= TIGHT_NUMERIC

private const val WIDE_COLUMNS = 4
private const val SPARSE_FILL_NUMERATOR = 100f
private const val SPARSE_FILL_OFFSET = 148f
private const val TIGHT_ROWS = 5
private const val TIGHT_FILL = 0.8f
private const val TIGHT_NUMERIC = 0.5f
