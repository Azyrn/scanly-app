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
    val notes: DocxNotes,
    /** Header and footer parts by relationship id ([PageSetup.headers], [PageSetup.footers]). */
    val parts: Map<String, List<DocxBlock>> = emptyMap(),
    /** settings.xml evenAndOddHeaders: even pages take the even header and footer. */
    val evenAndOddHeaders: Boolean = false,
    /**
     * Word 2013 and later (compatibilityMode 15) justify by shrinking spaces as well as widening them, so a
     * justified line holds a word that does not fit at full spacing ([ParagraphFactory.SPACE_SHRINK]).
     */
    val shrinkSpaces: Boolean = false,
    /** Footnotes by id (w:footnote w:id), drawn at the foot of the page that refers to them. */
    val footnotes: Map<String, List<DocxBlock>> = emptyMap(),
    /** The line between the body and the footnotes (the separator footnote). */
    val footnoteSeparator: List<DocxBlock> = emptyList()
)

/** A run of blocks sharing one page setup. [PageSetup.continuous] sections don't start a new page. */
internal data class DocxSection(val blocks: List<DocxBlock>, val page: PageSetup)

internal data class PageSetup(
    val widthTwips: Int = LETTER_WIDTH,
    val heightTwips: Int = LETTER_HEIGHT,
    val marginTop: Int = INCH,
    val marginBottom: Int = INCH,
    val marginLeft: Int = INCH,
    val marginRight: Int = INCH,
    val continuous: Boolean = false,
    /** pgMar header / footer: the header's top and the footer's bottom from the page edge. */
    val headerTwips: Int = HALF_INCH,
    val footerTwips: Int = HALF_INCH,
    /** A negative top / bottom margin: the body keeps it even under a taller header or footer. */
    val fixedTop: Boolean = false,
    val fixedBottom: Boolean = false,
    /** w:titlePg: the section's first page takes the "first" header and footer. */
    val titlePage: Boolean = false,
    /** Header and footer parts this section names (relationship ids), after inheritance (§17.10.5). */
    val headers: Map<HeaderKind, String> = emptyMap(),
    val footers: Map<HeaderKind, String> = emptyMap(),
    /** w:pgNumType w:start: page numbering restarts at this number with the section. */
    val pageStart: Int? = null
)

/** Which header or footer a page takes (ST_HdrFtr). */
internal enum class HeaderKind { DEFAULT, FIRST, EVEN }

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
    val rows: List<DocxRow>,
    /** tblCellMar left and right in twips; null where the table doesn't set them (Word: 108). */
    val cellMarginLeft: Int? = null,
    val cellMarginRight: Int? = null,
    /** w:bidiVisual: a right-to-left table, its first column on the right. */
    val rtl: Boolean = false,
    /** w:tblpPr anchored to the page or its margins: a floating table at its own place, not in the flow. */
    val position: TablePosition? = null
) : DocxBlock

/**
 * Where a floating table sits ([xTwips], [yTwips] from its anchors' top-left), and the space it keeps below
 * itself. Only page- and margin-anchored tables are placed; one anchored to its paragraph flows as usual.
 */
internal data class TablePosition(
    val xTwips: Int,
    val yTwips: Int,
    val fromPage: Boolean,
    val xFromPage: Boolean,
    val belowTwips: Int
)

internal data class DocxRow(val cells: List<DocxCell>, val heightTwips: Int, val exactHeight: Boolean)

internal enum class VerticalMerge { NONE, RESTART, CONTINUE }

internal data class DocxCell(
    val span: Int,
    val merge: VerticalMerge,
    /** RGB without alpha, null for no shading. */
    val fill: Int?,
    val bordered: Boolean,
    val blocks: List<DocxBlock>,
    /** w:vAlign: where the content sits in a row taller than it. */
    val align: CellAlign = CellAlign.TOP
)

internal enum class CellAlign { TOP, CENTER, BOTTOM }

internal sealed interface DocxInline

/** Text with '\t' for tabs and '\n' for line breaks. */
internal data class DocxText(val text: String, val style: RunStyle) : DocxInline

/** A PAGE field: the number of the page it is drawn on; [cached] is what Word showed last. */
internal data class DocxPageField(val style: RunStyle, val cached: String) : DocxInline

/** A footnote reference: [mark] is the number shown (empty when custom text follows). */
internal data class DocxNoteRef(val id: String, val mark: String, val style: RunStyle) : DocxInline

/** w:footnoteRef inside a footnote: the number of the note it opens, filled in by the layout. */
internal data class DocxNoteMark(val style: RunStyle) : DocxInline

/** w:separator / w:continuationSeparator: Word's short rule above the footnotes. */
internal data class DocxSeparator(val style: RunStyle) : DocxInline

/** A picture part inside the zip. Zero extents mean "use the picture's own size". */
internal data class DocxImage(
    val entry: String,
    val widthEmu: Long,
    val heightEmu: Long,
    /** Set for a floating picture text does not wrap around (wrapNone, or behind the text): drawn at its place. */
    val anchor: ImageAnchor? = null
) : DocxInline

/**
 * Where a floating picture goes: [x] / [y] in EMU from the left / top of what [xFrom] / [yFrom] name (page,
 * margin, column, paragraph…), or aligned there ([xAlign], [yAlign]: left/center/right, top/center/bottom).
 */
internal data class ImageAnchor(
    val x: Long,
    val xFrom: String,
    val xAlign: String?,
    val y: Long,
    val yFrom: String,
    val yAlign: String?
)

internal data class ListLabel(val text: String, val style: RunStyle)

internal enum class ParagraphAlign { START, CENTER, END, JUSTIFY }

internal enum class LineRule { AUTO, EXACT, AT_LEAST }

/** Text after a [TabKind.START] stop starts at it; after an [TabKind.END] stop it ends there, [TabKind.CENTER] centres it. */
internal enum class TabKind { START, CENTER, END, CLEAR }

/** A tab stop [pos] twips from the page margin. */
internal data class TabStop(val pos: Int, val kind: TabKind)

/**
 * A level's tab stops. Unlike every other property they add up through the style chain: a level
 * adds stops to the ones it inherits and clears the ones it names (ECMA-376 §17.3.1.38).
 */
internal class TabStops(val stops: List<TabStop>) {
    fun over(base: TabStops): TabStops {
        val cleared = stops.filter { it.kind == TabKind.CLEAR }.map { it.pos }.toSet()
        val own = stops.filter { it.kind != TabKind.CLEAR }
        val taken = own.map { it.pos }.toSet()
        return TabStops((base.stops.filter { it.pos !in cleared && it.pos !in taken } + own).sortedBy { it.pos })
    }
}

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
    /**
     * The space before / after is Word's "auto" (the margin of an HTML paragraph, 14 pt). Like HTML
     * margins, two of them that meet collapse into one instead of adding up.
     */
    val autoBefore: Boolean = false,
    val autoAfter: Boolean = false,
    /** Stays on the page of the paragraph after it (a heading with its text). */
    val keepNext: Boolean = false,
    /** Never split over two pages. */
    val keepLines: Boolean = false,
    /** A page never gets a single first or last line of it. Word's default when a document says nothing. */
    val widowControl: Boolean = true,
    /** Custom tab stops, measured from the page margin; past the last one Word's default stops go on. */
    val tabs: List<TabStop> = emptyList(),
    /** The paragraph mark's formatting, which sizes an empty paragraph. */
    val mark: RunStyle = RunStyle(),
    /** w:framePr: the paragraph sits in a frame at its own place (consecutive ones share it). */
    val frame: FramePosition? = null
)

/**
 * A text frame (w:framePr) as Word reads it (MS-OI29500 §2.1.43): hAnchor and vAnchor default to the text;
 * [xTwips] / [yTwips] or [xAlign] / [yAlign] place it; [widthTwips] 0 is the text's width; [heightTwips] with
 * [exactHeight] fixes its height, else it is at least that tall; [wrapNone] lets the text run under it.
 */
internal data class FramePosition(
    val xTwips: Int,
    val yTwips: Int,
    val xAlign: String?,
    val yAlign: String?,
    val hAnchor: String,
    val vAnchor: String,
    val widthTwips: Int,
    val heightTwips: Int,
    val exactHeight: Boolean,
    val wrapNone: Boolean
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
    val hidden: Boolean = false,
    /** Horizontal scale of the glyphs in percent (w:w). */
    val scale: Int = FULL_SCALE,
    /** Space added after every character, in twips (w:spacing); negative tightens. */
    val spacing: Int = 0,
    /** Pair kerning (w:kern at or below the size): off unless the document asks for it. */
    val kerning: Boolean = false,
    /** How complex-script (Arabic) characters are set: Word sizes, bolds and fonts them separately. */
    val complex: ComplexScript = ComplexScript()
) {
    /** This style as complex-script text takes it: w:cs font, szCs, bCs, iCs in place of their plain twins. */
    fun complexScript(): RunStyle =
        copy(font = complex.font ?: font, halfPoints = complex.halfPoints, bold = complex.bold, italic = complex.italic)
}

/** w:rFonts w:cs, w:szCs, w:bCs, w:iCs. */
internal data class ComplexScript(
    val font: String? = null,
    val halfPoints: Int = DEFAULT_HALF_POINTS,
    val bold: Boolean = false,
    val italic: Boolean = false
)

internal const val FULL_SCALE = 100

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
private const val HALF_INCH = 720
private const val LETTER_WIDTH = 12_240
private const val LETTER_HEIGHT = 15_840
private const val SINGLE_LINE = 240

/** Word's own fallback when a file has no docDefaults: 10 pt. */
private const val DEFAULT_HALF_POINTS = 20
