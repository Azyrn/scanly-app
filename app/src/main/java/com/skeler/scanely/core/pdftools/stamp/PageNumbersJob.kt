package com.skeler.scanely.core.pdftools.stamp

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import androidx.annotation.StringRes
import com.skeler.scanely.R
import com.skeler.scanely.core.pdfeditor.engine.PageGeometry
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/** Where the number sits: the page's six edge positions (the middle row would cover content). */
enum class NumberPosition(val atTop: Boolean, val align: Paint.Align) {
    TOP_LEFT(true, Paint.Align.LEFT),
    TOP_CENTER(true, Paint.Align.CENTER),
    TOP_RIGHT(true, Paint.Align.RIGHT),
    BOTTOM_LEFT(false, Paint.Align.LEFT),
    BOTTOM_CENTER(false, Paint.Align.CENTER),
    BOTTOM_RIGHT(false, Paint.Align.RIGHT)
}

/** The text drawn; templates take the page's number and the last number shown. */
enum class NumberFormat(@param:StringRes val template: Int) {
    PLAIN(R.string.pdf_stamp_numbers_format_plain),
    PAGE(R.string.pdf_stamp_numbers_format_page),
    SLASH_TOTAL(R.string.pdf_stamp_numbers_format_slash),
    PAGE_OF_TOTAL(R.string.pdf_stamp_numbers_format_page_of)
}

enum class NumberSize(val pointSize: Float) { SMALL(9f), MEDIUM(11f), LARGE(14f) }

/** Distance from the page edge, in points. */
enum class NumberMargin(val points: Float) { NARROW(18f), NORMAL(30f), WIDE(48f) }

/**
 * [firstPage] is the 1-based page that gets [startNumber]; earlier pages (a cover, a contents
 * page) are left unnumbered.
 */
data class PageNumbersOptions(
    val position: NumberPosition = NumberPosition.BOTTOM_CENTER,
    val format: NumberFormat = NumberFormat.PLAIN,
    val startNumber: Int = 1,
    val firstPage: Int = 1,
    val size: NumberSize = NumberSize.MEDIUM,
    val margin: NumberMargin = NumberMargin.NORMAL
)

/**
 * Numbers a document's pages. Every numbered page gets its own stamp page at its own size, since
 * the text differs on each one anyway; that is also what keeps mixed page sizes (a landscape
 * insert, an A3 fold-out) numbered at the right edge rather than at an A4-shaped guess.
 */
@Singleton
class PageNumbersJob @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val stamper: StampRunner
) {

    suspend fun number(input: ToolJobInput, options: PageNumbersOptions): ToolResult {
        val document = input.documents.first()
        require(options.firstPage in 1..document.pageCount) { "first page out of range" }

        return stamper.stamp(document, input, SUFFIX_NUMBERED, "page numbers", StampLayer.OVER) { geometries ->
            plan(geometries, options)
        }
    }

    private fun plan(geometries: List<PageGeometry>, options: PageNumbersOptions): StampPlan {
        val numbered = (options.firstPage..geometries.size).toList()
        val lastNumber = options.startNumber + numbered.size - 1
        // One paint for every page: only the text and the page size change.
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT
            textSize = options.size.pointSize
            color = Color.BLACK
            textAlign = options.position.align
        }
        return StampPlan(
            sizes = numbered.map { StampSize.of(geometries[it - 1]) },
            targets = numbered.mapIndexed { index, page -> page to index + 1 },
            draw = { canvas, index, size ->
                val text = context.getString(options.format.template, options.startNumber + index, lastNumber)
                // A small page (a receipt, a label) mustn't push the number off its edge.
                val margin = min(options.margin.points, min(size.widthPt, size.heightPt) / MARGIN_DIVISOR)
                val x = when (options.position.align) {
                    Paint.Align.LEFT -> margin
                    Paint.Align.RIGHT -> size.widthPt - margin
                    else -> size.widthPt / 2f
                }
                val metrics = paint.fontMetrics
                val y = if (options.position.atTop) {
                    margin - metrics.ascent
                } else {
                    size.heightPt - margin - metrics.descent
                }
                canvas.drawText(text, x, y, paint)
            }
        )
    }
}

private const val SUFFIX_NUMBERED = "_numbered"

/** The margin never exceeds a quarter of the page's shorter side. */
private const val MARGIN_DIVISOR = 4f
