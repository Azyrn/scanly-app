package com.skeler.scanely.core.pdftools.pdf2office.exact

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Consecutive pages written as one section of flowing body text. [topPt] is the top margin (where a
 * page's first line starts), [leftPt] the left margin and [rightPt] the right edge of the body, all
 * fixed when the run starts; [footPt] is the lowest line of running text seen so far.
 */
private class FlowRun(
    val widthPt: Float,
    val heightPt: Float,
    val topPt: Float,
    val leftPt: Float,
    val rightPt: Float
) {
    var footPt = 0f
    var pages = 0
    val headers = RunHeaders()
}

/** The paragraph left open at the foot of a page: it goes on at the top of the next one. */
private class OpenPara(val setting: ParaSetting, val lastLine: ParaLine)

/** One page being written: what follows it decides how its last paragraph and its section end. */
private class FlowPage(
    val run: FlowRun,
    val page: HeldPage,
    val flow: List<Para>,
    /** The next page when it goes on in this section, else null. */
    val follows: HeldPage?,
    /** The last paragraph runs on into the next page's first. */
    val crossing: Boolean,
    /** The section ends in this page's last paragraph (its properties hold the section's). */
    val inline: Boolean,
    val anchors: String
) {
    val xml = StringBuilder()
    var cursor = (run.topPt * TWIPS).roundToInt()
}

/**
 * Writes a tagged PDF's running text as real Word paragraphs in the body of the document
 * ([ExactParagraphs]): headings with heading styles, list items with numbering, a paragraph that runs
 * over a page break as one paragraph. Everything else on the page (pictures, the page's own picture,
 * tables, notes) stays anchored where it was, in the page's first paragraph; repeated headers and
 * footers become the section's header ([RunHeaders]).
 *
 * The text keeps its place because every paragraph is set exactly: its lines at the PDF's pitch,
 * the space above it what the PDF left, every word fitted to its width ([ExactRuns]) so each line
 * breaks where it did. Pages break where the PDF's did, and say so: a paragraph that starts a page
 * has "page break before", a paragraph that runs on ([crosses]) holds a page break where its line
 * ended the page. Nothing is left to where the reader's own page would end: a line that came out a
 * word longer moves the lines under it on its page, never a page. Pages are written one behind, so
 * each knows the page after it.
 */
internal class ExactDocxFlow(private val writer: ExactDocxWriter, measurer: TextMeasurer) {
    val lists = DocxLists()
    private val paragraphs = FlowParagraphXml(writer, measurer, lists)
    private var held: HeldPage? = null
    private var run: FlowRun? = null
    private var open: OpenPara? = null

    /** The artifacts of the flowing page written last, to tell the next page's page number from its repeated text. */
    private var before: List<Block>? = null

    fun add(page: HeldPage) {
        val current = held
        held = page
        if (current != null) write(current, page, false)
    }

    /** Writes the page held back. [final]: the document ends here; otherwise pages follow that do not flow. */
    fun flush(final: Boolean) {
        val current = held
        held = null
        if (current != null) write(current, null, final)
    }

    private fun write(page: HeldPage, next: HeldPage?, final: Boolean) {
        val paras = page.paras
        if (paras == null) {
            before = null
            writer.writePositioned(page)
            return
        }
        val job = plan(page, paras, next, final)
        writeParas(job)
        trace(job)
        job.run.pages++
        writer.pagesWritten++
        writer.writeBody(job.xml.toString())
        if (job.follows == null) {
            if (!job.inline) writer.endSection(section(job.run))
            run = null
            open = null
        }
    }

    /** What is known of the page before writing it: its run, the page it flows on to, whether it crosses. */
    private fun plan(page: HeldPage, paras: PageParas, next: HeldPage?, final: Boolean): FlowPage {
        val state = run ?: start(page, next).also { run = it }
        val flow = paras.flow
        state.footPt = max(state.footPt, flow.last().bottomPt(page.heightPt))
        val follows = next?.takeIf { continues(state, it, paras.artifacts) }
        val carried = open
        val onlyCarried = flow.size == 1 && carried != null
        val carriedPitch = carried?.setting?.pitchPt.takeIf { onlyCarried }
        val crossing = follows != null && crosses(state, flow.last(), carriedPitch, follows.paras!!.flow.first())
        return FlowPage(
            state,
            page,
            flow,
            follows,
            crossing,
            inline = follows == null && !final && !onlyCarried,
            anchors = anchors(state, page, paras, next)
        )
    }

    /** The page's paragraphs; the first goes on with the one left open on the page before, if any. */
    private fun writeParas(job: FlowPage) {
        val carried = open
        job.flow.forEachIndexed { index, para ->
            val setting = if (index == 0 && carried != null) goOn(job, carried, para) else begin(job, index, para)
            job.cursor += para.lines.size * setting.lineTwips
            val runsOn = index == job.flow.lastIndex && job.crossing
            if (!runsOn) job.xml.append("</w:p>")
            open = if (runsOn) OpenPara(setting, para.lines.last()) else null
        }
    }

    /**
     * The page's anchored content: its pictures, tables and placed text. Its repeated header and footer
     * text goes to the section's header; what counts pages (told by the page beside it) stays here.
     */
    private fun anchors(state: FlowRun, page: HeldPage, paras: PageParas, next: HeldPage?): String {
        val number = writer.pagesWritten + 1
        val beside = next?.paras?.artifacts
        val repeated = if (beside != null) {
            RunHeaders.split(paras.artifacts, beside, 1).first
        } else {
            RunHeaders.split(paras.artifacts, before.orEmpty(), -1).first
        }
        val first = state.pages == 0
        state.headers.add(repeated, first, number)
        before = paras.artifacts
        return writer.anchors(page, paras.rest + state.headers.unmatched(paras.artifacts, first, number))
    }

    /** A paragraph that starts on this page. */
    private fun begin(job: FlowPage, index: Int, item: Para): ParaSetting {
        val numId = paragraphs.numbering(item)
        // A label Word cannot draw in its place stays text, in front of the first line.
        val para = if (item.label != null && numId == null) item.withLabelAsText() else item
        val isLast = index == job.flow.lastIndex
        val joined = if (isLast && job.crossing) job.follows!!.paras!!.flow.first() else null
        val setting = FlowSettings.of(para, joined, para.lines.first().u0.takeIf { numId != null }, job.run.rightPt)
        val top = (topPt(para, job.page.heightPt, setting.pitchPt) * TWIPS).roundToInt()
        val before = (top - job.cursor).coerceAtLeast(0)
        job.cursor += before
        val place = ParaPlace(
            job.run.leftPt,
            job.run.rightPt,
            before,
            startsPage = index == 0 && job.run.pages > 0,
            numId = numId,
            section = if (isLast && job.inline) section(job.run) else null
        )
        job.xml.append("<w:p>").append(paragraphs.properties(para, setting, place))
        if (index == 0) job.xml.append(job.anchors)
        paragraphs.appendLines(job.xml, para, setting, continued = false)
        return setting
    }

    /** The rest of the paragraph left open on the page before: a page break, then its lines on this page. */
    private fun goOn(job: FlowPage, carried: OpenPara, item: Para): ParaSetting {
        val para = if (item.label != null) item.withLabelAsText() else item
        job.xml.append(paragraphs.pageJoinXml(carried.lastLine, para.lines.first(), carried.setting))
        // The page's anchors go after its first run of text: LibreOffice gives a paragraph's lines after a
        // page break no indents when drawings come straight after the break.
        val text = StringBuilder()
        paragraphs.appendLines(text, para, carried.setting, continued = true)
        val firstRun = text.indexOf(RUN_END) + RUN_END.length
        job.xml.append(text, 0, firstRun).append(job.anchors).append(text, firstRun, text.length)
        return carried.setting
    }

    // --- the run ------------------------------------------------------------------------------

    private fun start(page: HeldPage, next: HeldPage?): FlowRun {
        val same = next?.takeIf { it.paras != null && it.widthPt == page.widthPt && it.heightPt == page.heightPt }
        val pages = listOfNotNull(page, same)
        val flows = pages.map { it.paras!!.flow }
        val top = pages.minOf { it.paras!!.flow.first().topPt(it.heightPt) }.coerceAtLeast(0f)
        val left = flows.minOf { flow -> flow.minOf { min(it.leftPt, it.firstPt) } }.coerceAtLeast(0f)
        val right = flows.maxOf { flow -> flow.maxOf { it.rightPt } } + FlowSettings.WRAP_SLACK_PT
        return FlowRun(page.widthPt, page.heightPt, top, left, min(right, page.widthPt))
    }

    /**
     * [next] can be the run's next page: it flows, is the same size, starts no higher than the top margin
     * and its running header and footer are the section's. [artifacts]: those of the page before it.
     */
    private fun continues(state: FlowRun, next: HeldPage, artifacts: List<Block>): Boolean {
        val paras = next.paras ?: return false
        val repeated = RunHeaders.split(paras.artifacts, artifacts, -1).first
        return next.widthPt == state.widthPt && next.heightPt == state.heightPt &&
            paras.flow.first().topPt(next.heightPt) >= state.topPt - TOP_TOLERANCE_PT &&
            state.headers.accepts(repeated, false, writer.pagesWritten + 2)
    }

    /**
     * Whether [last], the page's last paragraph, runs on as [first], the next page's first. A tagged PDF
     * from Word starts a new paragraph element on every page, so the tree cannot say; the page can: the
     * line at the foot is full (it reaches the right edge as a justified line does, or the next word
     * would not have fitted), and the next page goes on at the continuation edge, in the same size and
     * pitch, at the top margin (where the line after a page break lands).
     */
    private fun crosses(state: FlowRun, last: Para, carriedPitch: Float?, first: Para): Boolean {
        val pitch = carriedPitch ?: FlowSettings.joinedPitch(last, first)
        return sameText(last, first) && goesOn(last, first, pitch) && fullLine(state, last, first) &&
            abs(topPt(first, state.heightPt, pitch) - state.topPt) <= TOP_TOLERANCE_PT
    }

    private fun fullLine(state: FlowRun, last: Para, first: Para): Boolean {
        val line = last.lines.last()
        val right = if (last.lines.size > 1) last.rightPt else state.rightPt - FlowSettings.WRAP_SLACK_PT
        if (right - line.u1 <= JUSTIFIED_PT) return true
        val text = line.segments.last().tokens.last().text
        return text.last() !in SENTENCE_END &&
            paragraphs.wraps(line, first.lines.first(), right + FlowSettings.WRAP_SLACK_PT)
    }

    /** The body ends a little under the lowest line any page had: a line more on a page still fits it. */
    private fun section(state: FlowRun): String {
        val foot = min(state.heightPt, state.footPt + FOOT_SLACK_PT)
        return sectionProperties(
            state.widthPt,
            state.heightPt,
            state.topPt,
            state.heightPt - foot,
            state.leftPt,
            (state.widthPt - state.rightPt).coerceAtLeast(0f),
            writer.extras.headers(state.headers) { writer.textBox(it, state.heightPt) },
            titlePage = !state.headers.isEmpty
        )
    }

    private companion object {
        const val RUN_END = "</w:r>"
        const val TOP_TOLERANCE_PT = 1f

        /** A justified line ends within this of the paragraph's right edge; a last line only by chance. */
        const val JUSTIFIED_PT = 1f

        /** Room under the lowest line of a run's pages, about two lines. */
        const val FOOT_SLACK_PT = 28f
        const val SENTENCE_END = ".:;!?"
    }
}

private val DEBUG = System.getenv("FLOW_DEBUG") != null
private const val SIZE_TOLERANCE_PT = 0.6f
private const val PITCH_TOLERANCE_PT = 0.3f

private fun Para.withLabelAsText(): Para =
    Para(block, listOf(ParaLine(listOf(label!!) + lines.first().segments)) + lines.drop(1))

private fun trace(job: FlowPage) {
    if (!DEBUG) return
    System.err.println(
        "flow page ${job.run.pages} paras ${job.flow.size} top ${job.run.topPt} " +
            "written foot ${job.cursor / TWIPS} drawn foot ${job.flow.last().bottomPt(job.page.heightPt)} " +
            "crossing ${job.crossing} follows ${job.follows != null}"
    )
}

/** Both are running text of one kind and size, and [first] starts with no label of its own. */
private fun sameText(last: Para, first: Para): Boolean =
    last.block.kind != TagKind.HEADING && last.block.kind == first.block.kind && first.label == null &&
        abs(last.lines.last().sizePt - first.lines.first().sizePt) <= SIZE_TOLERANCE_PT

/** [first] has [last]'s pitch and starts where its lines after the first start, with no indent of its own. */
private fun goesOn(last: Para, first: Para, pitch: Float): Boolean {
    val start = first.lines.first().u0
    val edge = FlowSettings.EDGE_TOLERANCE_PT
    val ownShape = first.lines.size == 1 ||
        abs(first.pitchPt - pitch) <= PITCH_TOLERANCE_PT && abs(start - first.leftPt) <= edge
    val atEdge = if (last.lines.size > 1) abs(start - last.leftPt) <= edge else start <= last.firstPt + edge
    return ownShape && atEdge
}

/** Where [para]'s first line starts down the page when its lines are [pitch] apart. */
private fun topPt(para: Para, heightPt: Float, pitch: Float): Float =
    heightPt - para.lines.first().baselineV - pitch * ExactLayout.BASELINE_RATIO
