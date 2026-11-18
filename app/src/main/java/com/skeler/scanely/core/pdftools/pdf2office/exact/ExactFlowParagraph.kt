package com.skeler.scanely.core.pdftools.pdf2office.exact

import kotlin.math.roundToInt

/** What a flowing paragraph's properties depend on besides the paragraph itself. */
internal class ParaPlace(
    /** The section's left margin and the right edge of its body, in points from the page's left. */
    val marginLeftPt: Float,
    val bodyRightPt: Float,
    val beforeTwips: Int,
    val startsPage: Boolean,
    /** The numbering instance of a list item, or null. */
    val numId: Int?,
    /** The section properties of a section this paragraph ends, or null. */
    val section: String?
)

/** The WordprocessingML of one flowing paragraph: its properties, and its lines joined into running text. */
internal class FlowParagraphXml(
    private val writer: ExactDocxWriter,
    private val measurer: TextMeasurer,
    private val lists: DocxLists
) {
    private val lineJoin = DocxLineJoin(measurer)

    /**
     * The numbering instance for a list item whose label Word can draw in the room the PDF gave it
     * (in front of the text); null leaves the label as text.
     */
    fun numbering(para: Para): Int? {
        val segment = para.label ?: return null
        val text = segment.tokens.joinToString("") { (if (it.spaceBefore) " " else "") + it.text }
        val label = lists.label(text, para.block.list, para.block.level) ?: return null
        val style = segment.tokens.first().style
        val drawn = measurer.width(text.trim(), style.family, style.arabic, style.bold, style.italic, style.sizePt)
        if (segment.u0 + drawn > para.lines.first().u0) return null
        return lists.numId(label, style, para.block.list, para.block.level)
    }

    /** pStyle, pageBreakBefore, numPr, tabs, spacing, ind, jc, sectPr: the order of ECMA-376 §17.3.1.26. */
    fun properties(para: Para, setting: ParaSetting, place: ParaPlace): String {
        val xml = StringBuilder("<w:pPr>")
        if (para.block.kind == TagKind.HEADING) {
            xml.append("<w:pStyle w:val=\"Heading${para.block.level.coerceIn(1, HEADING_LEVELS)}\"/>")
        }
        if (place.startsPage) xml.append("<w:pageBreakBefore/>")
        place.numId?.let { xml.append("<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"$it\"/></w:numPr>") }
        if (setting.stops.isNotEmpty()) {
            val stops = setting.stops.joinToString(
                ""
            ) { "<w:tab w:val=\"left\" w:pos=\"${twips(it - place.marginLeftPt)}\"/>" }
            xml.append("<w:tabs>").append(stops).append("</w:tabs>")
        }
        xml.append("<w:spacing w:before=\"${place.beforeTwips}\" w:after=\"0\" ")
            .append("w:line=\"${setting.lineTwips}\" w:lineRule=\"exact\"/>")
        // A numbered item's label hangs in front of its text; any other first line is indented or hangs by itself.
        xml.append(indent(setting, place, if (place.numId != null) para.label!!.u0 else setting.firstPt))
        when (setting.align) {
            Align.CENTER -> xml.append("<w:jc w:val=\"center\"/>")
            Align.RIGHT -> xml.append("<w:jc w:val=\"right\"/>")
            Align.LEFT -> Unit
        }
        return xml.append(place.section.orEmpty()).append("</w:pPr>").toString()
    }

    private fun indent(setting: ParaSetting, place: ParaPlace, firstStartPt: Float): String {
        val first = twips(firstStartPt - setting.leftPt)
        val firstLine = when {
            first > 0 -> " w:firstLine=\"$first\""
            first < 0 -> " w:hanging=\"${-first}\""
            else -> ""
        }
        return "<w:ind w:left=\"${twips(setting.leftPt - place.marginLeftPt)}\" " +
            "w:right=\"${twips(place.bodyRightPt - setting.wrapPt)}\"$firstLine/>"
    }

    /** [para]'s lines as runs; [continued]: they go on a paragraph begun on the page before. */
    fun appendLines(xml: StringBuilder, para: Para, setting: ParaSetting, continued: Boolean) {
        para.lines.forEachIndexed { index, line ->
            val next = para.lines.getOrNull(index + 1)
            val join = next?.let { joinOf(line, it, setting) }
            val start = when {
                setting.align != Align.LEFT -> line.u0
                index == 0 && !continued -> setting.firstPt
                else -> setting.leftPt
            }
            appendLine(xml, line, start, join, setting)
            if (join != null) xml.append(joinXml(join, line))
        }
    }

    private fun appendLine(xml: StringBuilder, line: ParaLine, startPt: Float, join: Join?, setting: ParaSetting) {
        line.segments.forEachIndexed { s, segment ->
            if (s > 0) repeat(tabsBefore(line.segments[s - 1], segment, setting)) { xml.append("<w:r><w:tab/></w:r>") }
            val ends = s == line.segments.lastIndex && join != null
            val runs = writer.lineRuns(segment, if (s == 0) startPt else segment.u0, if (ends) join!! else Join.BREAK)
            runs.forEach { xml.append(runXml(it)) }
        }
    }

    /** Tab characters from the end of [before] to where [segment] starts: one per stop on the way. */
    private fun tabsBefore(before: Segment, segment: Segment, setting: ParaSetting): Int =
        setting.stops.count { it > before.u1 + STOP_TOLERANCE_PT && it < segment.u0 - STOP_TOLERANCE_PT } + 1

    private fun joinOf(line: ParaLine, next: ParaLine, setting: ParaSetting): Join {
        if (setting.hardBreaks) return Join.BREAK
        val room = if (setting.align == Align.LEFT) {
            setting.wrapPt - line.u1
        } else {
            setting.wrapPt - setting.leftPt - (line.u1 - line.u0)
        }
        val end = line.segments.last()
        val join = lineJoin.join(end, next.segments.first(), 0f, end.u1 + room)
        // The same paragraph whatever the line's length: a line break keeps a short line short.
        return if (join == Join.PARAGRAPH) Join.BREAK else join
    }

    /** Between the line that ended a page and the paragraph's next line: a page break, where the PDF's page ended. */
    fun pageJoinXml(line: ParaLine, next: ParaLine, setting: ParaSetting): String {
        val hyphenated = joinOf(line, next, setting) == Join.SOFT_HYPHEN
        return (if (hyphenated) softHyphenXml(line.segments.last().tokens.last().style) else "") +
            "<w:r><w:br w:type=\"page\"/></w:r>"
    }

    /** Whether [line] is full: the first word of [next] would not have fitted after it up to [rightPt]. */
    fun wraps(line: ParaLine, next: ParaLine, rightPt: Float): Boolean {
        val join = lineJoin.join(line.segments.last(), next.segments.first(), 0f, rightPt)
        return join == Join.SPACE || join == Join.SOFT_HYPHEN
    }

    private fun joinXml(join: Join, line: ParaLine): String {
        val last = line.segments.last().tokens.last()
        return when (join) {
            Join.BREAK, Join.PARAGRAPH -> "<w:r><w:br/></w:r>"
            Join.SOFT_HYPHEN -> softHyphenXml(last.style)
            // A compound broken after its hyphen ("Secretary-" / "General") goes on without a space.
            Join.SPACE -> if (endsInHyphen(last.text)) "" else spaceXml(last.style)
        }
    }

    private fun endsInHyphen(text: String) =
        text.length > 1 && text.last() in HYPHENS && text[text.length - 2].isLetterOrDigit()

    private fun twips(points: Float): Int = (points * TWIPS).roundToInt()

    private companion object {
        const val HEADING_LEVELS = 6
        const val STOP_TOLERANCE_PT = 0.5f
        const val HYPHENS = "-‐"
    }
}
