package com.skeler.scanely.core.pdftools.pdf2office.exact

import com.skeler.scanely.core.pdftools.pdf2office.EMU_PER_POINT
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Where a block's text box goes. [x]/[y]/[cx]/[cy] are EMUs of the unrotated box; [lineStarts]
 * are each line's start along its reading direction in points, for [ExactRuns].
 */
internal class BoxGeometry(
    val x: Long,
    val y: Long,
    val cx: Long,
    val cy: Long,
    val rotation: Int,
    /** "vert270" (reads upward) or "vert" (reads downward) for a quarter-turned line, else null. */
    val vertical: String?,
    val indentLeftTwips: Int,
    val firstLineTwips: Int,
    val lineStarts: List<Float>
)

private const val TWIPS_PER_POINT = 20f

/** Room past the measured line so a rounding difference never wraps it. */
private const val SLACK_EM = 2f
private const val ROTATION_UNITS = 60_000
private const val QUARTER = 90
private const val THREE_QUARTERS = 270
private const val FULL_TURN = 360
private const val HALF = 0.5f

internal fun boxGeometry(block: Block, pageHeightPt: Float): BoxGeometry {
    val lines = block.lines
    val slack = lines.maxOf { it.sizePt } * SLACK_EM
    return when {
        block.angleDeg == QUARTER || block.angleDeg == -QUARTER || block.angleDeg == THREE_QUARTERS ->
            quarterTurned(block, pageHeightPt, slack)
        block.angleDeg != 0 -> rotated(block, pageHeightPt, slack)
        block.rtl -> {
            val top = (pageHeightPt - lines.first().baselineV) - block.pitchPt * ExactLayout.BASELINE_RATIO
            val right = lines.maxOf { it.u1 }
            val left = lines.minOf { it.u0 } - slack
            BoxGeometry(
                emu(
                    left
                ),
                emu(top), emu(right - left), emu(block.pitchPt * lines.size), 0, null, 0, 0, lines.map { right }
            )
        }
        else -> upright(block, pageHeightPt, slack)
    }
}

/**
 * A quarter-turned line (a margin label, a rotated table header). LibreOffice ignores a text box's
 * own rotation, so the box stays upright and its text runs vertically instead. For text reading
 * upward the baseline is a vertical line at x = -v with the glyph tops to its left; reading
 * downward, x = v with the tops to the right.
 */
private fun quarterTurned(block: Block, pageHeightPt: Float, slack: Float): BoxGeometry {
    val pitch = block.pitchPt
    val line = block.lines.first()
    val length = line.u1 - line.u0 + slack
    val upward = block.angleDeg == QUARTER
    val baselineX = if (upward) -line.baselineV else line.baselineV
    val ratio = if (upward) ExactLayout.BASELINE_RATIO else 1 - ExactLayout.BASELINE_RATIO
    val left = baselineX - pitch * ratio
    // Reading upward the line starts at the bottom of the box: page y of its start is H - u0.
    val top = if (upward) pageHeightPt - line.u0 - length else pageHeightPt + line.u0
    return BoxGeometry(
        emu(
            left
        ),
        emu(top), emu(pitch), emu(length), 0, if (upward) "vert270" else "vert", 0, 0, listOf(line.u0)
    )
}

/**
 * Any other angle: rotated about the centre (Word honours this; LibreOffice draws it level). In PDF
 * space (y up) the line runs along d and its "up" is n; the unrotated box's centre sits 0.3 of a
 * line above the baseline (baseline at 0.8 of the line, centre at 0.5).
 */
private fun rotated(block: Block, pageHeightPt: Float, slack: Float): BoxGeometry {
    val pitch = block.pitchPt
    val height = pitch * block.lines.size
    val line = block.lines.first()
    val radians = Math.toRadians(block.angleDeg.toDouble())
    val dx = cos(radians).toFloat()
    val dy = sin(radians).toFloat()
    val width = line.u1 - line.u0 + slack
    val centreU = line.u0 + width / 2
    val centreV = line.baselineV + pitch * (ExactLayout.BASELINE_RATIO - HALF)
    val pdfX = centreU * dx - centreV * dy
    val pdfY = centreU * dy + centreV * dx
    val pageX = pdfX - width / 2
    val pageY = (pageHeightPt - pdfY) - height / 2
    // DrawingML turns clockwise on the page; a PDF angle turns counter-clockwise.
    val rotation = (((-block.angleDeg % FULL_TURN) + FULL_TURN) % FULL_TURN) * ROTATION_UNITS
    return BoxGeometry(emu(pageX), emu(pageY), emu(width), emu(height), rotation, null, 0, 0, listOf(line.u0))
}

/** Upright left-to-right text: the box from the leftmost line start, with the first line's indent. */
private fun upright(block: Block, pageHeightPt: Float, slack: Float): BoxGeometry {
    val lines = block.lines
    val top = (pageHeightPt - lines.first().baselineV) - block.pitchPt * ExactLayout.BASELINE_RATIO
    val base = if (lines.size > 1) lines.drop(1).minOf { it.u0 } else lines.first().u0
    val first = base + block.indentPt
    val boxLeft = minOf(base, first)
    val right = lines.maxOf { it.u1 } + slack
    val indentLeft = ((base - boxLeft) * TWIPS_PER_POINT).roundToInt()
    val firstLine = (block.indentPt * TWIPS_PER_POINT).roundToInt()
    val starts = lines.mapIndexed { index, _ -> if (index == 0) first else base }
    return BoxGeometry(
        emu(
            boxLeft
        ),
        emu(top), emu(right - boxLeft), emu(block.pitchPt * lines.size), 0, null, indentLeft, firstLine, starts
    )
}

/**
 * Lengths laid end to end, in whole twips with the edges rounded as running totals: rounded one by
 * one, 8.98 pt rows (179.6 twips, written 180) drifted a fed_h8 table 0.3 pt down in 15 rows.
 */
internal fun onTwipGrid(lengthsPt: List<Float>): List<Int> {
    var exact = 0.0
    var written = 0
    return lengthsPt.map { length ->
        exact += length * TWIPS_PER_POINT
        val edge = Math.round(exact).toInt().coerceAtLeast(written + 1)
        (edge - written).also { written = edge }
    }
}

/**
 * Where a table goes, in page points: [top] of its first row, [lefts] of its columns, [widthsPt]
 * with room past the last column so a rounding difference never wraps it, and [heightsPt] from
 * each row's top to the next one's (the last row its own line). Shared by the Word and
 * PowerPoint writers.
 */
internal class TableGeometry(
    val top: Float,
    val lefts: List<Float>,
    val widthsPt: List<Float>,
    val heightsPt: List<Float>,
    /** Each row's exact line; the row's baseline sits at [ExactLayout.BASELINE_RATIO] of it. */
    val lineHeightsPt: List<Float>
)

/**
 * Rows are placed by their baselines. A row's line is its natural line, or the baseline pitch to
 * the row above or below when that is tighter (a 7 pt tax table at 7.5 pt). Each row starts where
 * its line puts its baseline and runs to the next row's start; since no line is taller than either
 * pitch beside it, no row has to grow and no baseline moves. Lines are cut short only where rows
 * are tight, never across the whole table.
 */
internal fun tableGeometry(table: ExactTable): TableGeometry {
    val columns = table.rows.maxOf { it.cells.size }
    val widths = table.columnWidthsPt.takeIf { it.size == columns }
        ?: List(columns) { table.box.width / columns }
    val slack = widths.toMutableList().apply { this[lastIndex] += table.fontSize * SLACK_EM }
    val lefts = widths.runningFold(table.box.left) { left, width -> left + width }.dropLast(1)
    val baselines = table.rows.map { it.box.top + it.box.height * ExactLayout.BASELINE_RATIO }
    val pitches = baselines.zipWithNext { a, b -> b - a }
    val lines = table.rows.mapIndexed { i, row ->
        min(row.box.height, min(pitches.getOrElse(i - 1) { row.box.height }, pitches.getOrElse(i) { row.box.height }))
            .coerceAtLeast(MIN_ROW_PT)
    }
    val tops = baselines.mapIndexed { i, b -> b - lines[i] * ExactLayout.BASELINE_RATIO }
    val heights = tops.zipWithNext { a, b -> (b - a).coerceAtLeast(MIN_ROW_PT) } + lines.last()
    return TableGeometry(tops.first(), lefts, slack, heights, lines)
}

private const val MIN_ROW_PT = 1f

private fun emu(points: Float): Long = (points * EMU_PER_POINT).roundToLong()
