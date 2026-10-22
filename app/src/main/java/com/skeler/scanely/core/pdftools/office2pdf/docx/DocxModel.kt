package com.skeler.scanely.core.pdftools.office2pdf.docx

/*
 * What DocxReader hands DocxLayout: a .docx reduced to the parts this renderer can draw, with
 * every style chain already resolved. Resolving in the reader keeps the layout free of Word's
 * inheritance rules, and keeps those rules testable on the JVM, where nothing can be drawn.
 *
 * Lengths stay in the units the file uses (twips, half-points, EMU) so nothing is rounded twice;
 * the layout converts once, to points.
 */

internal data class DocxDocument(
    val sections: List<DocxSection>,
    val notes: DocxNotes
)

/** A run of blocks sharing one page setup. [continuous] sections don't start a new page. */
internal data class DocxSection(val blocks: List<DocxBlock>, val page: PageSetup)

internal data class PageSetup(
    val widthTwips: Int = LETTER_WIDTH,
    val heightTwips: Int = LETTER_HEIGHT,
    val marginTop: Int = INCH,
    val marginBottom: Int = INCH,
    val marginLeft: Int = INCH,
    val marginRight: Int = INCH,
    val continuous: Boolean = false
)

internal sealed interface DocxBlock

internal data class DocxParagraph(
    val style: ParagraphStyle,
    val inlines: List<DocxInline>,
    val label: ListLabel? = null
) : DocxBlock

internal data object DocxPageBreak : DocxBlock

internal data class DocxTable(
    /** tblGrid widths in twips; may be empty or disagree with the rows, which the layout tolerates. */
    val columns: List<Int>,
    val rows: List<DocxRow>
) : DocxBlock

internal data class DocxRow(val cells: List<DocxCell>, val heightTwips: Int, val exactHeight: Boolean)

internal enum class VerticalMerge { NONE, RESTART, CONTINUE }

internal data class DocxCell(
    val span: Int,
    val merge: VerticalMerge,
    /** RGB without alpha, null for no shading. */
    val fill: Int?,
    val bordered: Boolean,
    val blocks: List<DocxBlock>
)

internal sealed interface DocxInline

/** Text with '\t' for tabs and '\n' for line breaks. */
internal data class DocxText(val text: String, val style: RunStyle) : DocxInline

/** A picture part inside the zip. Zero extents mean "use the picture's own size". */
internal data class DocxImage(val entry: String, val widthEmu: Long, val heightEmu: Long) : DocxInline

internal data class ListLabel(val text: String, val style: RunStyle)

internal enum class ParagraphAlign { START, CENTER, END, JUSTIFY }

internal enum class LineRule { AUTO, EXACT, AT_LEAST }

internal enum class VerticalAlign { BASELINE, SUPER, SUB }

/** Fully resolved paragraph formatting. Lengths in twips; [firstLine] is negative for a hanging indent. */
internal data class ParagraphStyle(
    val styleId: String? = null,
    val alignment: ParagraphAlign = ParagraphAlign.START,
    val spaceBefore: Int = 0,
    val spaceAfter: Int = 0,
    /** 240ths of a line for [LineRule.AUTO], twips otherwise. */
    val line: Int = SINGLE_LINE,
    val lineRule: LineRule = LineRule.AUTO,
    val indentLeft: Int = 0,
    val indentRight: Int = 0,
    val firstLine: Int = 0,
    val contextualSpacing: Boolean = false,
    /** The paragraph mark's formatting, which sizes an empty paragraph. */
    val mark: RunStyle = RunStyle()
)

/** Fully resolved character formatting. Colours are RGB without alpha. */
internal data class RunStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val halfPoints: Int = DEFAULT_HALF_POINTS,
    val color: Int? = null,
    val background: Int? = null,
    val font: String? = null,
    val verticalAlign: VerticalAlign = VerticalAlign.BASELINE,
    val caps: Boolean = false,
    val hidden: Boolean = false
)

/**
 * What the conversion left out or changed, counted while reading and laying out, so the result
 * can say so instead of silently looking incomplete. Mutable because both stages add to it.
 */
internal class DocxNotes {
    var charts = 0
    var equations = 0
    var shapes = 0
    var diagrams = 0
    var objects = 0
    var unreadableImages = 0
    var clippedRows = 0
    var fontsReplaced = false
    var headersFooters = false
    var footnotes = false
    var columns = false
    var pageLimitReached = false

    fun add(other: DocxNotes) {
        charts += other.charts
        equations += other.equations
        shapes += other.shapes
        diagrams += other.diagrams
        objects += other.objects
        unreadableImages += other.unreadableImages
        clippedRows += other.clippedRows
        fontsReplaced = fontsReplaced || other.fontsReplaced
        headersFooters = headersFooters || other.headersFooters
        footnotes = footnotes || other.footnotes
        columns = columns || other.columns
        pageLimitReached = pageLimitReached || other.pageLimitReached
    }
}

/** One OPC relationship; [target] is a zip entry name unless [external]. */
internal data class Relationship(val type: String, val target: String, val external: Boolean)

internal const val TWIPS_PER_POINT = 20f
internal const val EMU_PER_POINT = 12_700f

private const val INCH = 1440
private const val LETTER_WIDTH = 12_240
private const val LETTER_HEIGHT = 15_840
private const val SINGLE_LINE = 240

/** Word's own fallback when a file has no docDefaults: 10 pt. */
private const val DEFAULT_HALF_POINTS = 20
