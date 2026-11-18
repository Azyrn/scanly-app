package com.skeler.scanely.core.pdftools.office2pdf.docx

/**
 * Footnotes at the foot of the page that refers to them, as Word places them by default (pageBottom):
 * the separator, then the notes in reference order, ending at the bottom of the body. A line goes on a
 * page only with the notes it refers to; when they do not fit, the line moves on with them. A note is
 * kept whole (Word would continue a very long one on the next page).
 */
internal class Footnotes(private val document: DocxDocument, private val tables: TableLayout) {
    private val measured = HashMap<String, List<CellItem>>()
    private var separator: Pair<Float, List<CellItem>>? = null

    /** The items of the notes [refs] name on [flow]'s page, with the separator when they open its notes. */
    fun items(refs: List<NoteRef>, flow: PageFlow, first: Boolean = !flow.hasNotes): List<CellItem> =
        items(refs, flow.box.contentWidth, flow.box.contentHeight * IMAGE_PAGE_SHARE, first)

    /**
     * What of [items] (a line's notes, separator first when they open the page's notes) must fit on the page
     * with the line: up to the first paragraph of its first note — Word continues the rest of a long note on
     * the next page. Nothing once this page already carries notes over: these follow them.
     */
    fun needed(items: List<CellItem>, flow: PageFlow): Float {
        if (items.isEmpty() || flow.carrying) return 0f
        val separator = if (flow.hasNotes) 0 else separatorCount(flow.box.contentWidth)
        val head = items.take(separator)
        val note = items.getOrNull(separator) ?: return head.totalHeight()
        return head.totalHeight() + note.leastHeight
    }

    /** The items of the notes [refs] name, measured to [width]; with the separator first when [first]. */
    fun items(refs: List<NoteRef>, width: Float, maxImageHeight: Float, first: Boolean): List<CellItem> {
        if (refs.isEmpty()) return emptyList()
        val notes = refs.flatMap { ref ->
            measured.getOrPut("${ref.id}\u0000$width") {
                val blocks = document.footnotes[ref.id] ?: return@getOrPut emptyList()
                val marked = mapInlines(blocks) { if (it is DocxNoteMark) DocxText(ref.mark, it.style) else it }
                tables.measure(marked, width, maxImageHeight)
            }
        }
        if (notes.isEmpty() || !first) return notes
        return separator(width, maxImageHeight) + notes
    }

    /** How many items the separator measures to at [width] (0 until it has been measured). */
    fun separatorCount(width: Float): Int = separator?.takeIf { it.first == width }?.second?.size ?: 0

    fun separator(width: Float, maxImageHeight: Float): List<CellItem> {
        separator?.takeIf { it.first == width }?.let { return it.second }
        return tables.measure(document.footnoteSeparator, width, maxImageHeight).also { separator = width to it }
    }
}

/** The footnotes referred to on [line] of [plan]. */
internal fun refsOn(plan: ParagraphPlan, line: Int): List<NoteRef> = plan.notesOn(line).map { NoteRef(it.id, it.mark) }

/** A reference to footnote [id], shown as [mark]. */
internal class NoteRef(val id: String, val mark: String)

internal fun List<CellItem>.totalHeight(): Float = sumOf { it.height.toDouble() }.toFloat()
