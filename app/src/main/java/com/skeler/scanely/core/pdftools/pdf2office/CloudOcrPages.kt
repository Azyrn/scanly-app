package com.skeler.scanely.core.pdftools.pdf2office

import com.skeler.scanely.core.pdftools.ToolDocument
import com.skeler.scanely.core.pdftools.pdf2office.exact.ExactOcrWords
import com.skeler.scanely.core.pdftools.pdf2office.exact.ExactPage
import com.skeler.scanely.core.pdftools.pdf2office.exact.OcrBlock
import java.io.File

/**
 * Reads pages of a PDF again from their images, in the cloud, as paragraphs with their boxes.
 * Used only for the words a PDF's own text cannot be trusted for (Word's Arabic exports).
 */
fun interface CloudPageReader {
    /**
     * Each of [pages] (0-based, ascending) of [document] → its paragraphs, all pages in one request.
     * Empty when the pages can't be read (offline, signed out, no credits left, any error): the
     * conversion then keeps the PDF's own text, as it would without this reader.
     */
    suspend fun read(document: ToolDocument, pages: List<Int>, scratch: File): Map<Int, List<OcrBlock>>
}

/**
 * Pages of one conversion held back until the cloud has read the uncertain ones: the pages are
 * read in order, those with an uncertain word ([ExactOcrWords.uncertain]) are sent together in one
 * request, and their words are corrected ([ExactOcrWords.applyBlocks]) before anything is written.
 * Pages before the first uncertain one are written at once, so a document that needs nothing
 * holds nothing back.
 */
internal class CloudOcrPages(
    private val reader: CloudPageReader,
    private val document: ToolDocument,
    private val scratch: File,
    private val write: (ExactPage) -> Unit
) {
    private val held = ArrayList<Pair<Int, ExactPage>>()
    private val uncertain = ArrayList<Int>()

    /** Page [index], in document order. */
    fun add(index: Int, page: ExactPage) {
        if (ExactOcrWords.uncertain(page.layout)) uncertain += index
        if (uncertain.isEmpty()) write(page) else held += index to page
    }

    /** Reads the uncertain pages (one request), corrects them and writes every page held back. */
    suspend fun finish() {
        val blocks = if (uncertain.isEmpty()) emptyMap() else reader.read(document, uncertain, scratch)
        held.forEach { (index, page) ->
            val read = blocks[index]
            write(if (read.isNullOrEmpty()) page else corrected(page, read))
        }
        held.clear()
    }

    private fun corrected(page: ExactPage, blocks: List<OcrBlock>): ExactPage {
        val layout = ExactOcrWords.applyBlocks(page.layout, blocks)
        return ExactPage(page.widthPt, page.heightPt, layout, page.background, page.pictures)
    }
}
