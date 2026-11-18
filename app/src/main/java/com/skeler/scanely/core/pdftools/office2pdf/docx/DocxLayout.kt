package com.skeler.scanely.core.pdftools.office2pdf.docx

import android.content.res.AssetManager
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
    assets: AssetManager,
    private val notes: DocxNotes,
    private val maxPages: Int = MAX_PAGES
) {
    private val images = DocxImages(zip)
    private val floats = FloatingPictures(images)
    private val paragraphs = ParagraphFactory(images, DocxFonts(assets), notes)
    private val tables = TableLayout(paragraphs)
    private var footnotes = Footnotes(DocxDocument(emptyList(), notes), tables)
    private val placed = FloatLayout(tables)

    suspend fun render(document: DocxDocument, out: OutputStream, onProgress: (Float?) -> Unit) {
        val pdf = PdfDocument()
        try {
            paragraphs.shrinkSpaces = document.shrinkSpaces
            footnotes = Footnotes(document, tables)
            val headers = PageDecor(document, tables, floats)
            val flow = PageFlow(pdf, maxPages, headers::draw) { box ->
                footnotes.separator(box.contentWidth, box.contentHeight * IMAGE_PAGE_SHARE)
            }
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
            var index = 0
            while (index < section.blocks.size) {
                currentCoroutineContext().ensureActive()
                val used = renderBlock(section.blocks, index, flow)
                index += used
                done += used
                if (done % PROGRESS_EVERY < used) onProgress(done.toFloat() / total)
            }
        }
    }

    /** Lays out [blocks][index] (with the paragraphs sharing its frame); returns how many blocks it took. */
    private suspend fun renderBlock(blocks: List<DocxBlock>, index: Int, flow: PageFlow): Int {
        val block = blocks[index]
        val frame = (block as? DocxParagraph)?.style?.frame
        when {
            frame != null -> return placed.frame(framed(blocks, index, frame), frame, flow)
            block is DocxParagraph -> renderParagraph(block, flow, blocks.subList(index + 1, blocks.size))
            block is DocxTable -> renderTable(block, flow)
            else -> flow.breakPage()
        }
        return 1
    }

    /**
     * Space between paragraphs is the first one's "after" plus the second one's "before", both
     * dropped between same-style paragraphs that ask for contextual spacing. At the top of a page
     * Word keeps a paragraph's "before" after a page break someone made (a hard break, a new section,
     * the document's start) and drops it where the page simply filled up.
     */
    private suspend fun renderParagraph(paragraph: DocxParagraph, flow: PageFlow, following: List<DocxBlock>) {
        val box = flow.box
        val plans = plansOf(paragraph, box)
        if (paragraph.style.keepNext && !flow.atTop) keepWithNext(paragraph, plans, following, flow)
        flow.ensurePage()
        val before = plans.first().spaceBefore
        flow.y += if (flow.atTop) (if (flow.filledUp) 0f else before) else gapBefore(paragraph.style, before, flow)
        if (paragraph.inlines.any { it is DocxImage && it.anchor != null }) {
            floats.draw(flow.canvas, paragraph, flow.box, flow.y)
        }
        for (plan in plans) placeLines(plan, paragraph.style, flow)
        flow.pendingAfter = plans.last().spaceAfter
        flow.previous = paragraph.style
    }

    /** Plans made while looking ahead for "keep with next", so each paragraph is laid out once. */
    private val planned = java.util.IdentityHashMap<DocxParagraph, List<ParagraphPlan>>()

    private fun plansOf(paragraph: DocxParagraph, box: PageBox): List<ParagraphPlan> =
        planned.remove(paragraph) ?: paragraphs.build(paragraph, box.contentWidth, box.contentHeight * IMAGE_PAGE_SHARE)

    /**
     * "Keep with next": the paragraph, every paragraph after it that is also kept with its next, and
     * the first lines of the one after those go on one page. When they do not fit on what is left of
     * this page but would fit a fresh one, the page ends here. A chain taller than a page is not kept.
     */
    private fun keepWithNext(
        first: DocxParagraph,
        plans: List<ParagraphPlan>,
        following: List<DocxBlock>,
        flow: PageFlow
    ) {
        var needed = gapBefore(first.style, plans.first().spaceBefore, flow) + totalHeight(plans)
        var previous = first
        var after = plans.last().spaceAfter
        // The chain: paragraphs up to and including the first one that is not itself kept with its next.
        val chain = following.take(MAX_KEPT).takeWhile { it is DocxParagraph }.filterIsInstance<DocxParagraph>()
        val end = chain.indexOfFirst { !it.style.keepNext }.let { if (it < 0) chain.size else it + 1 }
        for (next in chain.take(end)) {
            val nextPlans = paragraphs.build(next, flow.box.contentWidth, flow.box.contentHeight * IMAGE_PAGE_SHARE)
            planned[next] = nextPlans
            needed += spaceBetween(previous.style, next.style, after, nextPlans.first().spaceBefore)
            needed += if (next.style.keepNext) totalHeight(nextPlans) else firstChunk(nextPlans.first(), next.style)
            previous = next
            after = nextPlans.last().spaceAfter
        }
        val tooLow = flow.y + needed + flow.notesHeight > flow.bottom + EPSILON
        if (tooLow && needed <= flow.box.contentHeight) flow.finishPage(filledUp = true)
    }

    private fun totalHeight(plans: List<ParagraphPlan>): Float = plans.sumOf { it.textHeight.toDouble() }.toFloat()

    /** The least of a paragraph that may stand at the foot of a page. */
    private fun firstChunk(plan: ParagraphPlan, style: ParagraphStyle): Float =
        plan.height(0, PageRules.leastAtFoot(plan.lineCount, style))

    private fun gapBefore(style: ParagraphStyle, spaceBefore: Float, flow: PageFlow): Float {
        val previous = flow.previous ?: return spaceBefore + flow.pendingAfter
        return spaceBetween(previous, style, flow.pendingAfter, spaceBefore)
    }

    private fun spaceBetween(previous: ParagraphStyle, style: ParagraphStyle, after: Float, before: Float): Float =
        PageRules.spaceBetween(previous, style, after, before)

    private suspend fun placeLines(plan: ParagraphPlan, style: ParagraphStyle, flow: PageFlow) {
        var start = 0
        while (start < plan.lineCount) {
            currentCoroutineContext().ensureActive()
            flow.ensurePage()
            val fits = fittingLines(plan, start, flow)
            val end = if (flow.atTop) fits else PageRules.breakAt(plan.lineCount, style, start, fits)
            if (end == start) {
                flow.finishPage(filledUp = true)
                continue
            }
            plan.draw(flow.canvas, flow.box.left, flow.y, start, end)
            flow.used = true
            flow.y += plan.height(start, end)
            for (line in start until end) flow.addNotes(footnotes.items(refsOn(plan, line), flow))
            start = end
            if (start < plan.lineCount) flow.finishPage(filledUp = true)
        }
    }

    /**
     * At the top of a page one line always goes in, even one taller than the page, or nothing would. A line
     * fits only with the footnotes it refers to ([Footnotes]) below it on the page.
     */
    private fun fittingLines(plan: ParagraphPlan, start: Int, flow: PageFlow): Int {
        var end = start
        var height = 0f
        var notes = 0f
        while (end < plan.lineCount) {
            val line = plan.lineHeight(end)
            val first = !flow.hasNotes && notes == 0f
            val more = footnotes.needed(footnotes.items(refsOn(plan, end), flow, first), flow)
            val fits = flow.y + height + line + flow.notesHeight + notes + more <= flow.bottom + EPSILON
            if (!fits && (end > start || !flow.atTop)) break
            height += line
            notes += more
            end++
        }
        return end
    }

    private suspend fun renderTable(table: DocxTable, flow: PageFlow) {
        table.position?.let { return placed.table(table, it, flow) }
        flow.ensurePage()
        // Pictures in its cells placed on the page itself (the reader floats no other in a table).
        floats.draw(flow.canvas, listOf(table), flow.box, flow.y)
        if (!flow.atTop) flow.y += flow.pendingAfter
        val edges = tables.columns(table, flow.box.contentWidth)
        val fills = HashMap<Int, Int?>()
        for (index in table.rows.indices) {
            currentCoroutineContext().ensureActive()
            val row = table.rows[index]
            val plan = tables.row(table, row, edges, flow.box.contentHeight * IMAGE_PAGE_SHARE)
            val refs = noteRefs(row.cells.flatMap { it.blocks }).map { NoteRef(it.id, it.mark) }
            val rowNotes = footnotes.needed(footnotes.items(refs, flow), flow)
            if (flow.y + plan.height + flow.notesHeight + rowNotes > flow.bottom + EPSILON && !flow.atTop) {
                flow.finishPage(filledUp = true)
            }
            flow.ensurePage()
            if (plan.height > flow.box.contentHeight + EPSILON) notes.clippedRows++
            val origin = RowOrigin(flow.box.left, flow.y, flow.bottom)
            tables.drawRow(flow.canvas, table, index, plan, origin, fills)
            flow.used = true
            flow.y += plan.height
            flow.addNotes(footnotes.items(refs, flow))
        }
        flow.pendingAfter = 0f
        flow.previous = null
    }

    companion object {
        /** Past this a conversion stops and says so; a bigger PDF wouldn't be usable on a phone. */
        const val MAX_PAGES = 2000

        private const val PROGRESS_EVERY = 16
        private const val EPSILON = 0.01f

        /** How many paragraphs "keep with next" may chain before the chain is no longer looked at. */
        private const val MAX_KEPT = 8
    }
}

private class PageLimitReached : Exception()

/** A picture may fill most of a page but leave room for the line it sits on. */
internal const val IMAGE_PAGE_SHARE = 0.95f

/** Rounding room when a note's height is checked against the page foot. */
private const val NOTE_SLACK = 0.01f

/** A page's size in whole points and its text area, clamped so a hostile sectPr can't make it unusable. */
internal class PageBox(setup: PageSetup) {
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
internal class PageFlow(
    private val pdf: PdfDocument,
    private val maxPages: Int,
    /** Draws a new page's header and footer and says where its body goes. */
    private val decorate: (PageFrame, Canvas) -> BodyBand,
    /** The footnote separator for a page of this size. */
    private val separator: (PageBox) -> List<CellItem>
) {
    var box = PageBox(PageSetup())
        private set
    var setup = PageSetup()
        private set
    private var page: PdfDocument.Page? = null
    private var pages = 0
    var y = 0f

    /** The lowest point the body may reach on this page: the bottom margin, or above a tall footer. */
    var bottom = 0f

    /** The number the page shows (PAGE): counts on from the previous page unless a section restarts it. */
    var pageNumber = 0
        private set
    private var restartAt: Int? = null

    /** The page is the first its section started (titlePg's "first" header). */
    var firstOfSection = false
        private set
    private var sectionPages = 0

    /** The footnotes of this page, drawn at its foot when it ends, and the height they take. */
    private val noteItems = ArrayList<CellItem>()
    var notesHeight = 0f
        private set
    val hasNotes: Boolean get() = noteItems.isNotEmpty()

    /** Notes that did not fit this page's foot: they open the next page's notes, under the separator. */
    private val carried = ArrayList<CellItem>()
    val carrying: Boolean get() = carried.isNotEmpty()

    /** Adds the notes of lines just placed (the body now ends at [y]); what does not fit goes on to the next page. */
    fun addNotes(items: List<CellItem>, separators: Int = 0) {
        items.forEachIndexed { index, item -> addNote(item, index, separators) }
    }

    /** [item], the [index]th of a batch opened by [separators] separator items: here, cut, or carried over. */
    private fun addNote(item: CellItem, index: Int, separators: Int) {
        if (carried.isNotEmpty()) {
            carried += item
            return
        }
        val room = bottom + NOTE_SLACK - y - notesHeight
        val note = index >= separators
        val parts = if (note && item.height > room) item.split(room) else null
        if (parts != null) {
            place(parts.first)
            carried += parts.second
        } else if (item.height <= room || note && opens(index, separators)) {
            place(item)
        } else {
            carried += item
        }
    }

    /** The page's first note goes in even when taller than the room, or it would never be placed. */
    private fun opens(index: Int, separators: Int): Boolean = noteItems.size <= separators && index <= separators

    private fun place(item: CellItem) {
        noteItems += item
        notesHeight += item.height
    }

    /** The previous paragraph's space after, owed only if something follows on this page. */
    var pendingAfter = 0f
    var previous: ParagraphStyle? = null

    val canvas: Canvas get() = ensurePage().canvas

    /** Nothing is on the page yet (space above its first paragraph does not count). */
    val atTop: Boolean get() = page == null || !used

    /** Set once something is drawn on the page. */
    var used = false

    /** The page before this one ended because it was full, not at a break someone made. */
    var filledUp = false
        private set

    /** Continuous sections share the page when its size doesn't change; others start a new one. */
    fun beginSection(setup: PageSetup) {
        val next = PageBox(setup)
        if (!setup.continuous || !next.sameSize(box)) finishPage()
        box = next
        this.setup = setup
        // A continuous section that starts on a page already begun has had its first page.
        sectionPages = if (page != null) 1 else 0
        setup.pageStart?.let { restartAt = it }
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
        pageNumber = restartAt ?: (pageNumber + 1)
        restartAt = null
        firstOfSection = sectionPages == 0
        sectionPages++
        val band = decorate(PageFrame(box, setup, pageNumber, firstOfSection), started.canvas)
        y = band.top
        bottom = band.bottom
        noteItems.clear()
        notesHeight = 0f
        if (carried.isNotEmpty()) {
            val notes = carried.toList()
            carried.clear()
            // A separator item is never carried alone: a page's notes start with it again.
            val rule = separator(box).filter { it !in notes }
            addNotes(rule + notes, rule.size)
        }
        used = false
        pendingAfter = 0f
        previous = null
        return started
    }

    /** [filledUp]: the page ends because the next thing does not fit, so the text runs on. */
    fun finishPage(filledUp: Boolean = false) {
        page?.let { finished ->
            var top = bottom - notesHeight
            for (item in noteItems) {
                item.draw(finished.canvas, box.left, top)
                top += item.height
            }
            pdf.finishPage(finished)
        }
        page = null
        this.filledUp = filledUp
    }

    /** A document with no content still becomes a one-page PDF rather than an unreadable empty one. */
    fun close() {
        if (pages == 0) ensurePage()
        finishPage()
        // Footnotes still carried over after the last line go on pages of their own.
        while (carried.isNotEmpty() && pages < maxPages) {
            ensurePage()
            finishPage()
        }
    }
}

/** Word's rules for where paragraphs break over pages and how much space goes between them. */
internal object PageRules {
    /** Widow and orphan control keeps at least this many lines of a paragraph on each side of a page break. */
    private const val WIDOW_LINES = 2

    /**
     * Where a paragraph of [count] lines may break when lines [start] until [fits] fit the page: not at all
     * when its lines are kept together; with widow and orphan control never leaving one line alone at the
     * foot of this page or at the top of the next (a paragraph of three lines moves whole). [start] means
     * the whole paragraph moves to the next page.
     */
    fun breakAt(count: Int, style: ParagraphStyle, start: Int, fits: Int): Int {
        if (fits >= count) return count
        if (style.keepLines) return start
        if (!style.widowControl) return fits
        var end = fits
        if (count - end < WIDOW_LINES) end = count - WIDOW_LINES
        return if (start == 0 && end < WIDOW_LINES) start else end.coerceAtLeast(start)
    }

    /** How many of a paragraph's [count] lines must fit at the foot of a page: all of them, two, or one. */
    fun leastAtFoot(count: Int, style: ParagraphStyle): Int = when {
        style.keepLines -> count
        style.widowControl -> if (count == WIDOW_LINES + 1) count else minOf(count, WIDOW_LINES)
        else -> minOf(count, 1)
    }

    /**
     * The space between a paragraph in [previous] style ending with [after] and one in [style] starting with
     * [before]: both, each dropped between same-style paragraphs that ask for contextual spacing. Where either
     * side is automatic (HTML) spacing the two collapse into the larger, as HTML margins do. Measured on Word's
     * PDF of ca_dfo_wave_fetch_a: an empty Normal paragraph (after 8 pt) followed by one with automatic before
     * (14 pt) is 14 pt apart, not 22; two automatic sides there were 14 pt apart, not 28.
     */
    fun spaceBetween(previous: ParagraphStyle, style: ParagraphStyle, after: Float, before: Float): Float {
        val joined = previous.styleId == style.styleId
        val above = if (joined && previous.contextualSpacing) 0f else after
        val below = if (joined && style.contextualSpacing) 0f else before
        return if (previous.autoAfter || style.autoBefore) maxOf(above, below) else above + below
    }
}
