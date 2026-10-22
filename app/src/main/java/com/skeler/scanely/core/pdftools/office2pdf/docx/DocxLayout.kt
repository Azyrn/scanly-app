package com.skeler.scanely.core.pdftools.office2pdf.docx

import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.OutputStream
import java.util.zip.ZipFile

/**
 * Lays a [DocxDocument] out onto PDF pages: paragraphs flow line by line and continue on the
 * next page mid-paragraph, tables flow row by row, and every section gets its own page size and
 * margins. Canvas units are points, the PDF's own unit, so twips convert once (÷20) and font
 * sizes are used as they are.
 *
 * Simplifications go into [notes]: rows taller than a page are cut, and output stops at
 * [maxPages] rather than producing a file no phone could open.
 */
internal class DocxLayout(
    zip: ZipFile,
    private val notes: DocxNotes,
    private val maxPages: Int = MAX_PAGES
) {
    private val paragraphs = ParagraphFactory(DocxImages(zip), notes)
    private val tables = TableLayout(paragraphs)

    suspend fun render(document: DocxDocument, out: OutputStream, onProgress: (Float?) -> Unit) {
        val pdf = PdfDocument()
        try {
            val flow = PageFlow(pdf, maxPages)
            try {
                renderSections(document, flow, onProgress)
            } catch (ignored: PageLimitReached) {
                notes.pageLimitReached = true
            }
            flow.close()
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    private suspend fun renderSections(document: DocxDocument, flow: PageFlow, onProgress: (Float?) -> Unit) {
        val total = document.sections.sumOf { it.blocks.size }.coerceAtLeast(1)
        var done = 0
        for (section in document.sections) {
            flow.beginSection(section.page)
            for (block in section.blocks) {
                currentCoroutineContext().ensureActive()
                when (block) {
                    is DocxParagraph -> renderParagraph(block, flow)
                    is DocxTable -> renderTable(block, flow)
                    DocxPageBreak -> flow.breakPage()
                }
                done++
                if (done % PROGRESS_EVERY == 0) onProgress(done.toFloat() / total)
            }
        }
    }

    /**
     * Space between paragraphs is the first one's "after" plus the second one's "before", both
     * dropped between same-style paragraphs that ask for contextual spacing, and both dropped at
     * the top of a page.
     */
    private suspend fun renderParagraph(paragraph: DocxParagraph, flow: PageFlow) {
        val box = flow.box
        val plans = paragraphs.build(paragraph, box.contentWidth, box.contentHeight * IMAGE_PAGE_SHARE)
        val gap = gapBefore(paragraph.style, plans.first().spaceBefore, flow)
        flow.ensurePage()
        if (!flow.atTop) flow.y += gap
        for (plan in plans) placeLines(plan, flow)
        flow.pendingAfter = plans.last().spaceAfter
        flow.previous = paragraph.style
    }

    private fun gapBefore(style: ParagraphStyle, spaceBefore: Float, flow: PageFlow): Float {
        val previous = flow.previous ?: return spaceBefore + flow.pendingAfter
        val joined = previous.styleId == style.styleId
        val after = if (joined && previous.contextualSpacing) 0f else flow.pendingAfter
        val before = if (joined && style.contextualSpacing) 0f else spaceBefore
        return after + before
    }

    private suspend fun placeLines(plan: ParagraphPlan, flow: PageFlow) {
        var start = 0
        while (start < plan.lineCount) {
            currentCoroutineContext().ensureActive()
            flow.ensurePage()
            val end = fittingLines(plan, start, flow)
            if (end == start) {
                flow.finishPage()
                continue
            }
            plan.draw(flow.canvas, flow.box.left, flow.y, start, end)
            flow.y += plan.layout.getLineTop(end) - plan.layout.getLineTop(start)
            start = end
            if (start < plan.lineCount) flow.finishPage()
        }
    }

    /** At the top of a page one line always goes in, even one taller than the page, or nothing would. */
    private fun fittingLines(plan: ParagraphPlan, start: Int, flow: PageFlow): Int {
        var end = start
        var height = 0f
        while (end < plan.lineCount) {
            val line = plan.lineHeight(end)
            val fits = flow.y + height + line <= flow.box.bottom + EPSILON
            if (!fits && (end > start || !flow.atTop)) break
            height += line
            end++
        }
        return end
    }

    private suspend fun renderTable(table: DocxTable, flow: PageFlow) {
        flow.ensurePage()
        if (!flow.atTop) flow.y += flow.pendingAfter
        val edges = tables.columns(table, flow.box.contentWidth)
        val fills = HashMap<Int, Int?>()
        for (index in table.rows.indices) {
            currentCoroutineContext().ensureActive()
            val plan = tables.row(table.rows[index], edges, flow.box.contentHeight * IMAGE_PAGE_SHARE)
            if (flow.y + plan.height > flow.box.bottom + EPSILON && !flow.atTop) flow.finishPage()
            flow.ensurePage()
            if (plan.height > flow.box.contentHeight + EPSILON) notes.clippedRows++
            val origin = RowOrigin(flow.box.left, flow.y, flow.box.bottom)
            tables.drawRow(flow.canvas, table, index, plan, origin, fills)
            flow.y += plan.height
        }
        flow.pendingAfter = 0f
        flow.previous = null
    }

    companion object {
        /** Past this a conversion stops and says so; a bigger PDF wouldn't be usable on a phone. */
        const val MAX_PAGES = 2000

        private const val PROGRESS_EVERY = 16

        /** A picture may fill most of a page but leave room for the line it sits on. */
        private const val IMAGE_PAGE_SHARE = 0.95f
        private const val EPSILON = 0.01f
    }
}

private class PageLimitReached : Exception()

/** A page's size in whole points and its text area, clamped so a hostile sectPr can't make it unusable. */
private class PageBox(setup: PageSetup) {
    val width = (setup.widthTwips / TWIPS_PER_POINT).toInt().coerceIn(MIN_PAGE, MAX_PAGE)
    val height = (setup.heightTwips / TWIPS_PER_POINT).toInt().coerceIn(MIN_PAGE, MAX_PAGE)
    private val horizontal = (width - MIN_CONTENT) / 2f
    private val vertical = (height - MIN_CONTENT) / 2f
    val left = (setup.marginLeft / TWIPS_PER_POINT).coerceIn(0f, horizontal)
    val right = width - (setup.marginRight / TWIPS_PER_POINT).coerceIn(0f, horizontal)
    val top = (setup.marginTop / TWIPS_PER_POINT).coerceIn(0f, vertical)
    val bottom = height - (setup.marginBottom / TWIPS_PER_POINT).coerceIn(0f, vertical)
    val contentWidth get() = right - left
    val contentHeight get() = bottom - top

    fun sameSize(other: PageBox): Boolean = width == other.width && height == other.height

    private companion object {
        const val MIN_PAGE = 144
        const val MAX_PAGE = 14_400
        const val MIN_CONTENT = 72
    }
}

/**
 * The page being filled and the cursor on it. A page is started lazily, when something is about
 * to be drawn, so a page break or section start never leaves an empty page behind by itself.
 */
private class PageFlow(private val pdf: PdfDocument, private val maxPages: Int) {
    var box = PageBox(PageSetup())
        private set
    private var page: PdfDocument.Page? = null
    private var pages = 0
    var y = 0f

    /** The previous paragraph's space after, owed only if something follows on this page. */
    var pendingAfter = 0f
    var previous: ParagraphStyle? = null

    val canvas: Canvas get() = ensurePage().canvas

    val atTop: Boolean get() = page == null || y <= box.top + TOP_TOLERANCE

    /** Continuous sections share the page when its size doesn't change; others start a new one. */
    fun beginSection(setup: PageSetup) {
        val next = PageBox(setup)
        if (!setup.continuous || !next.sameSize(box)) finishPage()
        box = next
    }

    /** An explicit break: the next content starts a fresh page, even if this one is still empty. */
    fun breakPage() {
        ensurePage()
        finishPage()
    }

    fun ensurePage(): PdfDocument.Page {
        page?.let { return it }
        if (pages >= maxPages) throw PageLimitReached()
        val started = pdf.startPage(PdfDocument.PageInfo.Builder(box.width, box.height, pages + 1).create())
        page = started
        pages++
        y = box.top
        pendingAfter = 0f
        previous = null
        return started
    }

    fun finishPage() {
        page?.let { pdf.finishPage(it) }
        page = null
    }

    /** A document with no content still becomes a one-page PDF rather than an unreadable empty one. */
    fun close() {
        if (pages == 0) ensurePage()
        finishPage()
    }

    private companion object {
        const val TOP_TOLERANCE = 0.01f
    }
}
