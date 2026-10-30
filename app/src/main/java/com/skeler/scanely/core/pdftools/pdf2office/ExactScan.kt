package com.skeler.scanely.core.pdftools.pdf2office

import android.util.Log
import com.skeler.scanely.R
import com.skeler.scanely.core.ocr.paddle.PaddleLineReader
import com.skeler.scanely.core.pdftools.ToolDocument
import com.skeler.scanely.core.pdftools.ToolNote
import com.skeler.scanely.core.pdftools.pdf2office.exact.ExactPage
import com.skeler.scanely.core.pdftools.pdf2office.exact.ExactPageLayout
import com.skeler.scanely.core.pdftools.pdf2office.exact.ExactPageSource
import com.skeler.scanely.core.pdftools.pdf2office.exact.ScanTextLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "ExactScan"

/** What the exact converters met on the way, for the result notes. */
internal class ExactTally {
    var unreadable = 0
    var searchable = 0
    var pictureOnly = 0

    fun notes(): List<ToolNote> {
        val tally = PageTally().also {
            it.unreadable = unreadable
            it.scanned = pictureOnly
        }
        return buildList {
            if (searchable > 0) add(ToolNote(R.plurals.pdf2office_scanned_searchable, searchable, plural = true))
            addAll(tally.notes(null, R.plurals.pdf2office_scanned_as_images))
        }
    }
}

/**
 * Page [index] ready to write: read, its tables settled ([confirmTables]) and, for a scan, its text
 * recognised behind the picture ([recogniseScan]). Null when pdfium can't read the page. Counts both
 * in [tally].
 */
internal suspend fun exactPage(
    source: OfficePageSource,
    reader: PaddleLineReader,
    document: ToolDocument,
    pages: ExactPageSource,
    index: Int,
    scratch: File,
    tally: ExactTally
): ExactPage? {
    val page = pages.read(index, scratch)?.let { confirmTables(source, document, pages, index, it) }
    if (page == null) {
        tally.unreadable++
        return null
    }
    val scan = page.layout.isEmpty && (page.pictures.isNotEmpty() || page.background != null)
    if (!scan) return page
    val read = recogniseScan(reader, pages, index, page)
    if (read != null) tally.searchable++ else tally.pictureOnly++
    return if (read == null) page else ExactPage(page.widthPt, page.heightPt, read, page.background, page.pictures)
}

/**
 * [page] with its tables settled by the layout model when the text alone couldn't
 * ([ExactPageLayout.unconfirmedTables]); otherwise [page] as it is.
 */
internal suspend fun confirmTables(
    source: OfficePageSource,
    document: ToolDocument,
    pages: ExactPageSource,
    index: Int,
    page: ExactPage
): ExactPage {
    if (!page.layout.unconfirmedTables) return page
    val regions = source.tableRegions(document, index) ?: return page
    val layout = pages.layout(index, regions) ?: return page
    return ExactPage(page.widthPt, page.heightPt, layout, page.background, page.pictures)
}

/**
 * The text of a page that has none of its own (a scan), read from its picture with the bundled
 * PaddleOCR models, as an invisible layer over the picture. Null when it can't be read or finds
 * nothing: the page then stays a picture, as before.
 */
internal suspend fun recogniseScan(
    reader: PaddleLineReader,
    pages: ExactPageSource,
    index: Int,
    page: ExactPage
): ExactPageLayout? =
    withContext(Dispatchers.Default) {
        val bitmap = pages.renderForOcr(index, page.widthPt, page.heightPt) ?: return@withContext null
        try {
            val lines = runCatching { reader.read(bitmap) }
                .onFailure { Log.w(TAG, "No text read from page $index", it) }
                .getOrNull()
            if (lines.isNullOrEmpty()) {
                null
            } else {
                ScanTextLayout.layout(lines, bitmap.width, bitmap.height, page.widthPt, page.heightPt)
            }
        } finally {
            bitmap.recycle()
        }
    }
