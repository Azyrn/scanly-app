package com.skeler.scanely.core.pdftools.pdf2office.exact

import com.skeler.scanely.core.pdftools.pdf2office.NS_OFFICE_RELS
import com.skeler.scanely.core.pdftools.pdf2office.XML_HEADER
import kotlin.math.abs

/**
 * Running headers and footers of a run of flowing pages ([ExactDocxFlow]). A tagged PDF marks them as
 * artifacts; here the ones that repeat become the section's real header, so they are no paragraphs of
 * the document's body and are edited once for every page.
 *
 * A section has three headers: for its first page, for odd pages and for even pages (books mirror
 * them). Each is fixed by the first page that uses it; a later page whose repeating text differs
 * cannot be in the section ([accepts]). Text that counts pages (a page number: it differs by one from
 * the page beside it) is not repeated text and stays on its page. Header and footer text both go in
 * the header part, as text boxes placed on the page: a header may draw anywhere on it.
 */
internal class RunHeaders {
    private val slots = arrayOfNulls<List<Block>>(SLOTS)

    /** The header [page] uses: the first page's, else by the parity of its number in the document. */
    private fun slot(first: Boolean, number: Int): Int = if (first) {
        FIRST
    } else if (number % 2 == 0) {
        EVEN
    } else {
        ODD
    }

    /** Whether a page with the repeating text [repeated] can use this section's headers. */
    fun accepts(repeated: List<Block>, first: Boolean, number: Int): Boolean {
        val set = slots[slot(first, number)] ?: return true
        return set.size == repeated.size && set.zip(repeated).all { (a, b) -> same(a, b) }
    }

    fun add(repeated: List<Block>, first: Boolean, number: Int) {
        val slot = slot(first, number)
        if (slots[slot] == null) slots[slot] = repeated
    }

    /** [artifacts] of a page that its header does not already show: they stay on the page. */
    fun unmatched(artifacts: List<Block>, first: Boolean, number: Int): List<Block> {
        val set = slots[slot(first, number)].orEmpty()
        return artifacts.filter { block -> set.none { same(it, block) } }
    }

    val first: List<Block> get() = slots[FIRST].orEmpty()
    val odd: List<Block> get() = slots[ODD].orEmpty()
    val even: List<Block> get() = slots[EVEN].orEmpty()
    val isEmpty: Boolean get() = slots.all { it.isNullOrEmpty() }

    companion object {
        private const val SLOTS = 3
        private const val FIRST = 0
        private const val ODD = 1
        private const val EVEN = 2
        private const val PLACE_TOLERANCE_PT = 1.5f
        private val DIGITS = Regex("[0-9]+")

        /**
         * [artifacts] of a page split into the text that repeats and the text that counts pages: a block
         * that the page beside it ([beside], [step] pages on) has on the same line with the same words
         * and one number that differs by [step].
         */
        fun split(artifacts: List<Block>, beside: List<Block>, step: Int): Pair<List<Block>, List<Block>> =
            artifacts.partition { block -> beside.none { counts(block, it, step) } }

        private fun counts(block: Block, other: Block, step: Int): Boolean {
            val a = text(block)
            val b = text(other)
            val sameLine = block.lines.size == 1 && other.lines.size == 1 &&
                abs(block.lines[0].baselineV - other.lines[0].baselineV) <= PLACE_TOLERANCE_PT
            if (!sameLine || DIGITS.replace(a, "#") != DIGITS.replace(b, "#")) return false
            val pairs = DIGITS.findAll(a).map { it.value }.toList().zip(DIGITS.findAll(b).map { it.value }.toList())
            val changed = pairs.filter { (x, y) -> x != y }
            return changed.size == 1 && changed.all { (x, y) -> stepOf(x, y) == step.toLong() }
        }

        private fun stepOf(from: String, to: String): Long? {
            val a = from.toLongOrNull() ?: return null
            val b = to.toLongOrNull() ?: return null
            return b - a
        }

        private fun same(a: Block, b: Block): Boolean =
            a.lines.size == b.lines.size && text(a) == text(b) && a.lines.zip(b.lines).all { (x, y) ->
                abs(x.baselineV - y.baselineV) <= PLACE_TOLERANCE_PT && abs(x.u0 - y.u0) <= PLACE_TOLERANCE_PT
            }

        private fun text(block: Block): String =
            block.lines.joinToString(
                "\n"
            ) { line -> line.tokens.joinToString("") { (if (it.spaceBefore) " " else "") + it.text } }
    }
}

/** A part of the .docx package next to word/document.xml, with its relationship and content type. */
internal class DocxPart(
    val name: String,
    val xml: String,
    val relationId: String,
    val relationType: String,
    val contentType: String
)

/**
 * The parts a flowing document adds to the package: one header part per section header, the setting
 * that gives odd and even pages headers of their own, and the numbering of its lists.
 */
internal class DocxExtraParts {
    /** word/header<n>.xml, in order; [empty] is the number of the one that shows nothing. */
    private val headers = ArrayList<String>()
    private var empty = 0

    /**
     * The header references of a flowing section: its first page's, odd pages' and even pages' running
     * text ([RunHeaders]), each block drawn by [box]. Empty until a section has such text. From then on
     * every section names its headers, or it would repeat the section's before it.
     */
    fun headers(run: RunHeaders, box: (Block) -> String): String {
        if (run.isEmpty && headers.isEmpty()) return ""
        return listOf(
            "first" to run.first,
            "default" to run.odd,
            "even" to run.even
        ).joinToString("") { (type, blocks) ->
            reference(type, if (blocks.isEmpty()) emptyHeader() else part(blocks.joinToString("", transform = box)))
        }
    }

    /** For a section without running text of its own: nothing, where the section before it had some. */
    fun plainHeaders(): String =
        if (headers.isEmpty()) "" else reference("default", emptyHeader()) + reference("even", emptyHeader())

    /** Every extra part to write, [lists] included when the document has list items. */
    fun parts(lists: DocxLists): List<DocxPart> {
        val parts = ArrayList<DocxPart>()
        if (!lists.isEmpty) {
            parts += DocxPart(
                "numbering.xml",
                lists.xml(),
                "rIdNumbering",
                "$NS_OFFICE_RELS/numbering",
                "$TYPES.numbering+xml"
            )
        }
        if (headers.isNotEmpty()) {
            // Odd and even pages have headers of their own (a book's mirrored pages): a document setting.
            val settings = "$XML_HEADER<w:settings xmlns:w=\"$NS_WORD_MAIN\"><w:evenAndOddHeaders/></w:settings>"
            parts += DocxPart(
                "settings.xml",
                settings,
                "rIdSettings",
                "$NS_OFFICE_RELS/settings",
                "$TYPES.settings+xml"
            )
        }
        headers.forEachIndexed { index, xml ->
            val n = index + 1
            parts += DocxPart("header$n.xml", xml, "rIdHeader$n", "$NS_OFFICE_RELS/header", "$TYPES.header+xml")
        }
        return parts
    }

    private fun reference(type: String, part: Int) = "<w:headerReference w:type=\"$type\" r:id=\"rIdHeader$part\"/>"

    private fun emptyHeader(): Int {
        if (empty == 0) empty = part("")
        return empty
    }

    /** A header is one 1 pt paragraph holding its text as boxes placed on the page. */
    private fun part(boxes: String): Int {
        headers += "$XML_HEADER<w:hdr $DOCUMENT_NAMESPACES><w:p><w:pPr>$TINY_LINE</w:pPr>$boxes</w:p></w:hdr>"
        return headers.size
    }

    private companion object {
        const val TYPES = "application/vnd.openxmlformats-officedocument.wordprocessingml"
    }
}
