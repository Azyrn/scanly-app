package com.skeler.scanely.core.pdftools.pdf2office.exact

import com.skeler.scanely.core.pdfeditor.engine.PdfPageTags

/** What a tagged PDF says a piece of text is. */
internal enum class TagKind {
    /** A paragraph of running text. */
    BODY,
    HEADING,

    /** A list item: its label is numbering, not text. */
    LIST,

    /** A footnote or endnote. */
    NOTE,

    /** Inside a table cell. */
    CELL,

    /** Anything placed outside the running text: a text box, a figure, a caption of one. */
    FLOATING
}

/**
 * The block of a tagged PDF's structure a piece of text belongs to: [node] is the paragraph-level
 * structure element (the same for every line of one paragraph on a page), [level] the heading level
 * (1 = top) or the list's depth (1 = outermost), [list] the list element a list item belongs to.
 */
internal class TagBlock(val node: Int, val kind: TagKind, val level: Int, val list: Int)

/**
 * Reads a page's link into the PDF's structure tree ([PdfPageTags]): which paragraph, heading, list
 * item, note or table cell each marked content belongs to. Structure types are the standard ones of
 * ISO 32000-1 §14.8.4 plus the names Microsoft Office writes without a role map entry of their own
 * ("Footnote", "Endnote", "Textbox", "Title"). Text with no place in the tree is an artifact: a
 * running header or footer, a page number.
 */
internal class ExactTags(private val tags: PdfPageTags) {
    private val blocks = HashMap<Int, TagBlock?>()

    /** The block of the marked content [mcid]; null for an artifact or an id the tree does not know. */
    fun block(mcid: Int): TagBlock? {
        val node = tags.node(mcid)
        if (node < 0) return null
        return blocks.getOrPut(node) { resolve(node) }
    }

    private fun resolve(start: Int): TagBlock? {
        val types = tags.structure.types
        val parents = tags.structure.parents
        val found = Found()
        var node = start
        var steps = 0
        while (node >= 0 && steps++ < MAX_DEPTH) {
            val type = types[node]
            if (found.block < 0 && paragraphLevel(type)) found.block = paragraphNode(node, type)
            found.see(type, node, headingLevel(type))
            node = parents[node]
        }
        if (found.block < 0) return null
        val kind = found.kind ?: TagKind.BODY
        return TagBlock(
            found.block,
            kind,
            if (kind == TagKind.LIST) found.depth.coerceAtLeast(1) else found.level,
            found.list
        )
    }

    private fun paragraphLevel(type: String): Boolean =
        type in PARAGRAPHS || type in ITEM_PARTS || headingLevel(type) > 0

    /**
     * The paragraph [node] of [type] belongs to. A list item's label and body are one paragraph: Word tags
     * the label apart, and puts an item's first line on a new page under the label too.
     */
    private fun paragraphNode(node: Int, type: String): Int {
        val parent = tags.structure.parents[node]
        val item = type in LIST_PARTS && parent >= 0 && tags.structure.types[parent] == "LI"
        return if (item) parent else node
    }

    /** What the way up from a marked content to the tree's root has shown so far. */
    private class Found {
        var block = -1
        var kind: TagKind? = null
        var level = 0
        var list = -1
        var depth = 0

        /**
         * An ancestor of type [type]: a floating box beats a note, which beats a cell, which beats a list item
         * or a heading; of those last two the innermost wins (a cell holds lists and headings).
         */
        fun see(type: String, node: Int, heading: Int) {
            when {
                type == "L" -> {
                    depth++
                    if (list < 0) list = node
                }
                heading > 0 -> if (kind == null) {
                    kind = TagKind.HEADING
                    level = heading
                }
                else -> kindOf(type)?.let { seen -> if (rank(seen) > rank(kind)) kind = seen }
            }
        }

        private fun kindOf(type: String): TagKind? = when {
            type in FLOATING -> TagKind.FLOATING
            type in NOTES -> TagKind.NOTE
            type in CELLS -> TagKind.CELL
            type == "LI" -> TagKind.LIST
            else -> null
        }

        private fun rank(kind: TagKind?): Int = when (kind) {
            TagKind.FLOATING -> RANK_FLOATING
            TagKind.NOTE -> RANK_NOTE
            TagKind.CELL -> RANK_CELL
            null -> 0
            else -> 1
        }
    }

    private fun headingLevel(type: String): Int = when {
        type == "Title" || type == "H" -> 1
        type.length == 2 && type[0] == 'H' && type[1] in '1'..'6' -> type[1] - '0'
        else -> 0
    }

    private companion object {
        const val MAX_DEPTH = 64
        val PARAGRAPHS = setOf("P", "Caption", "TOCI", "BlockQuote", "Quote", "Code", "Note", "BibEntry", "Index")

        /** A list item's parts hold text directly when the writer put no paragraph inside them (Word does not). */
        val ITEM_PARTS = setOf("LBody", "Lbl", "TD", "TH", "Footnote", "Endnote")
        val LIST_PARTS = setOf("LBody", "Lbl")
        val NOTES = setOf("Note", "Footnote", "Endnote")
        val CELLS = setOf("TD", "TH")
        val FLOATING = setOf("Textbox", "Figure", "Formula", "Form", "Art", "Annot", "Sect.Textbox")
        const val RANK_CELL = 2
        const val RANK_NOTE = 3
        const val RANK_FLOATING = 4
    }
}
