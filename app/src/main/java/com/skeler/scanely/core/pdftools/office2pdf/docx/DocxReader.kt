package com.skeler.scanely.core.pdftools.office2pdf.docx

import com.skeler.scanely.core.pdftools.ToolException
import com.skeler.scanely.core.pdftools.ToolFailure
import org.xmlpull.v1.XmlPullParser
import java.util.zip.ZipFile
import kotlin.coroutines.cancellation.CancellationException

/**
 * Reads a .docx into [DocxDocument] with one streaming pass over document.xml, so memory follows
 * the text, not the file's XML overhead.
 *
 * Only the main document must be well formed: a malformed one is [ToolFailure.Damaged]. Styles,
 * numbering and the theme are read leniently — losing them costs fidelity, not the document.
 * [newParser] is injectable because the platform parser exists only on a device; JVM tests pass
 * one built on the JDK's StAX reader.
 */
internal class DocxReader(private val newParser: () -> XmlPullParser = ::platformParser) {

    /** [checkCancelled] runs before every paragraph and table row; it throws to stop the read. */
    fun read(zip: ZipFile, checkCancelled: () -> Unit = {}): DocxDocument = try {
        ReadSession(zip, newParser, checkCancelled).read()
    } catch (e: Exception) {
        // Cancellation and our own failures pass through; anything else the parser or the zip
        // threw means the file isn't the document it claims to be.
        throw if (e is ToolException || e is CancellationException) e else ToolException(ToolFailure.Damaged, e)
    }
}

/** Where a top-level paragraph's sectPr ends a section, and the body's own sectPr ends the last. */
internal class SectionSink(val endSection: (PageSetup) -> Unit) {
    var finalPage: PageSetup? = null
}

internal class ReadSession(
    val zip: ZipFile,
    private val newParser: () -> XmlPullParser,
    private val cancellationCheck: () -> Unit
) {
    val notes = DocxNotes()
    var theme = ThemeFonts()
        private set
    var styles = StyleSheet()
        private set
    var numbering = Numbering()
        private set
    var rels: Map<String, Relationship> = emptyMap()
        private set

    /** The current table's style, which sits between document defaults and paragraph styles. */
    var tableStyle: Pair<ParaProps, RunProps>? = null

    val fields = FieldState()
    private val headerRefs = LinkedHashSet<String>()
    private val sectionReader = SectionReader(notes, headerRefs)
    private val inlines = DocxInlineReader(this)
    private val tables = DocxTableReader(this)

    fun read(): DocxDocument {
        val documentPath = mainDocumentPath(zip, newParser)
        if (zip.getEntry(documentPath) == null) throw damaged()
        rels = relationshipsOf(zip, documentPath, newParser)
        theme = auxiliary("/theme") { parseTheme(it) } ?: theme
        styles = auxiliary("/styles") { parseStyles(it, theme) } ?: styles
        numbering = auxiliary("/numbering") { parseNumbering(it, theme) } ?: numbering
        val sections = parsePart(zip, documentPath, DOCUMENT_CAP, newParser, ::parseDocument) ?: throw damaged()
        notes.headersFooters = headerRefs.any { id ->
            rels[id]?.takeUnless { it.external }?.let { partHasContent(zip, it.target, newParser) } == true
        }
        return DocxDocument(sections, notes)
    }

    private fun <T> auxiliary(typeSuffix: String, read: (XmlPullParser) -> T): T? {
        val part = rels.values.firstOrNull { it.type.endsWith(typeSuffix) && !it.external } ?: return null
        return try {
            parsePart(zip, part.target, PART_CAP, newParser, read)
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            null
        }
    }

    private fun parseDocument(parser: XmlPullParser): List<DocxSection> {
        var sections: List<DocxSection>? = null
        parser.children { name ->
            if (name == "body" && parser.isW()) sections = parseBody(parser) else parser.skip()
        }
        return sections ?: listOf(DocxSection(emptyList(), PageSetup()))
    }

    private fun parseBody(parser: XmlPullParser): List<DocxSection> {
        val sections = ArrayList<DocxSection>()
        val blocks = ArrayList<DocxBlock>()
        val sink = SectionSink { page ->
            sections += DocxSection(blocks.toList(), page)
            blocks.clear()
        }
        readBlocks(parser, blocks, sink)
        sections += DocxSection(blocks.toList(), sink.finalPage ?: PageSetup())
        return sections
    }

    /**
     * The block-level children of the current element: body, table cell, content control.
     * [claim] lets a caller take a child first (a cell's tcPr) by returning true after consuming it.
     */
    fun readBlocks(
        parser: XmlPullParser,
        out: MutableList<DocxBlock>,
        sink: SectionSink?,
        claim: ((String) -> Boolean)? = null
    ) {
        parser.children { name ->
            when {
                claim?.invoke(name) == true -> Unit
                parser.isW() -> readWordBlock(parser, name, out, sink)
                else -> readForeignBlock(parser, name, out)
            }
        }
    }

    private fun readWordBlock(parser: XmlPullParser, name: String, out: MutableList<DocxBlock>, sink: SectionSink?) {
        when (name) {
            "p" -> readParagraph(parser, out, sink)
            "tbl" -> out += tables.read(parser)
            "sdt" -> readSdt(parser) { readBlocks(parser, out, sink) }
            "customXml", "ins", "moveTo" -> readBlocks(parser, out, sink)
            "sectPr" -> if (sink != null) sink.finalPage = sectionReader.read(parser) else parser.skip()
            else -> parser.skip()
        }
    }

    /** Markup-compatibility wrappers and equations are the only non-Word blocks worth a look. */
    private fun readForeignBlock(parser: XmlPullParser, name: String, out: MutableList<DocxBlock>) {
        when (name) {
            "AlternateContent" -> out += readAlternateBlocks(parser)
            "oMathPara", "oMath" -> {
                notes.equations++
                parser.skip()
            }
            else -> parser.skip()
        }
    }

    /** The Fallback branch when there is one: it is what any consumer is guaranteed to understand. */
    private fun readAlternateBlocks(parser: XmlPullParser): List<DocxBlock> {
        var choice: List<DocxBlock>? = null
        var fallback: List<DocxBlock>? = null
        parser.children { branch ->
            val target = ArrayList<DocxBlock>()
            when {
                branch == "Fallback" -> fallback = target.also { readBlocks(parser, it, null) }
                branch == "Choice" && choice == null -> choice = target.also { readBlocks(parser, it, null) }
                else -> parser.skip()
            }
        }
        return fallback ?: choice.orEmpty()
    }

    /** A content control: only its content shows; its properties and placeholder state don't. */
    fun readSdt(parser: XmlPullParser, readContent: () -> Unit) {
        parser.children { name -> if (name == "sdtContent") readContent() else parser.skip() }
    }

    fun readParagraph(parser: XmlPullParser, out: MutableList<DocxBlock>, sink: SectionSink?) {
        checkCancelled()
        val paragraph = ParagraphAssembler(this, out, topLevel = sink != null)
        var section: PageSetup? = null
        parser.children { name ->
            if (name == "pPr" && parser.isW()) {
                val properties = parseParagraphProps(parser, theme, sectionReader.takeIf { sink != null })
                paragraph.properties = properties
                section = properties.section
            } else {
                inlines.read(parser, name, paragraph.context)
            }
        }
        paragraph.finish()
        section?.let { sink?.endSection?.invoke(it) }
    }

    fun checkCancelled() = cancellationCheck()

    private companion object {
        /** The zip-bomb guard for document.xml; real documents are rarely a tenth of this. */
        const val DOCUMENT_CAP = 64L * 1024 * 1024
    }
}

/**
 * Field codes: between "begin" and "separate" is the instruction (`PAGE`, `TOC \o`), which is
 * never shown; between "separate" and "end" is the result Word cached at last save, which is
 * what the reader would see. Fields nest, and may span paragraphs (a table of contents does).
 */
internal class FieldState {
    private val stack = ArrayList<Boolean>()

    /** True while inside an instruction, where run text must be dropped. */
    val inInstruction: Boolean get() = stack.any { !it }

    fun mark(type: String?) {
        when (type) {
            "begin" -> if (stack.size < MAX_NESTING) stack += false
            "separate" -> if (stack.isNotEmpty()) stack[stack.lastIndex] = true
            "end" -> stack.removeLastOrNull()
        }
    }

    private companion object {
        const val MAX_NESTING = 32
    }
}

/**
 * What run-level parsing needs from its surroundings. [runStyle] is set inside a run (its
 * resolved style); [runBase] is what a new run inherits from its paragraph.
 */
internal data class InlineContext(
    val runBase: () -> RunProps,
    val notes: DocxNotes,
    val emit: (DocxInline) -> Unit,
    val pageBreak: () -> Unit,
    val runStyle: (() -> RunStyle)? = null
)

/**
 * Collects one w:p. Formatting is resolved on first need, which is after pPr (always the first
 * child) and before any run. An explicit page break splits the paragraph in two around a
 * [DocxPageBreak], which is how the layout can treat breaks as blocks.
 */
internal class ParagraphAssembler(
    private val session: ReadSession,
    private val out: MutableList<DocxBlock>,
    private val topLevel: Boolean
) {
    var properties: ParagraphProperties? = null

    private val resolved by lazy { resolve() }
    private var segment = ArrayList<DocxInline>()
    private var segments = 0

    val context = InlineContext(
        runBase = { resolved.runBase },
        notes = session.notes,
        emit = ::emit,
        pageBreak = ::pageBreak
    )

    fun finish() = flush()

    private fun emit(inline: DocxInline) {
        val last = segment.lastOrNull()
        if (inline is DocxText && last is DocxText && last.style == inline.style) {
            segment[segment.lastIndex] = DocxText(last.text + inline.text, last.style)
        } else {
            segment += inline
        }
    }

    /** Inside a table cell Word ignores page breaks, so they become line breaks there. */
    private fun pageBreak() {
        if (!topLevel) {
            emit(DocxText("\n", resolved.runBase.toStyle()))
            return
        }
        flush()
        out += DocxPageBreak
    }

    private fun flush() {
        val style = resolved.style
        if (breaksBefore()) out += DocxPageBreak
        val segmentStyle = if (segments == 0) style else style.copy(spaceBefore = 0)
        out += DocxParagraph(segmentStyle, segment, resolved.label.takeIf { segments == 0 })
        segment = ArrayList()
        segments++
    }

    /** "Page break before" applies once, to the first segment, and never to a document's very first block. */
    private fun breaksBefore(): Boolean = segments == 0 && resolved.breakBefore && topLevel && out.isNotEmpty()

    private class Resolved(
        val style: ParagraphStyle,
        val runBase: RunProps,
        val label: ListLabel?,
        val breakBefore: Boolean
    )

    private fun resolve(): Resolved {
        val styles = session.styles
        val direct = properties?.props ?: ParaProps()
        val mark = properties?.mark ?: RunProps()
        val (styleParagraph, styleRun) = styles.paragraph(direct[ParaKey.STYLE])
        val (tableParagraph, tableRun) = session.tableStyle ?: (ParaProps() to RunProps())
        val inherited = styleParagraph.over(tableParagraph.over(styles.defaultPara))
        val runBase = styleRun.over(tableRun.over(styles.defaultRun))
        val markRun = mark.over(runBase)
        val numId = listNumId(direct, inherited)
        val level = listLevelIndex(direct, inherited)
        val listLevel = numId?.let { session.numbering.level(it, level, styles) }
        val effective = direct.over(listLevel?.para?.over(inherited) ?: inherited)
        return Resolved(
            style = effective.toStyle(markRun.toStyle()),
            runBase = runBase,
            label = listLabel(numId, level, listLevel, markRun),
            breakBefore = effective.get<Boolean>(ParaKey.PAGE_BREAK_BEFORE) == true
        )
    }

    /** The paragraph's list, direct over style; numId "0" explicitly takes it out of one. */
    private fun listNumId(direct: ParaProps, inherited: ParaProps): String? =
        (direct.get<String>(ParaKey.NUM_ID) ?: inherited.get<String>(ParaKey.NUM_ID))?.takeIf { it != NO_LIST }

    private fun listLevelIndex(direct: ParaProps, inherited: ParaProps): Int =
        (direct.get<Int>(ParaKey.LEVEL) ?: inherited.get<Int>(ParaKey.LEVEL) ?: 0)
            .coerceIn(0, Numbering.MAX_LEVELS - 1)

    /** Advances the list's counters (only for a real list level) and styles the label like the paragraph mark. */
    private fun listLabel(numId: String?, level: Int, listLevel: ListLevel?, markRun: RunProps): ListLabel? {
        val labelText = if (numId != null && listLevel != null) {
            session.numbering.nextLabel(numId, level, session.styles)
        } else {
            null
        }
        return labelText?.takeIf { it.isNotEmpty() }?.let { text ->
            ListLabel(text, labelStyle(listLevel?.run?.over(markRun) ?: markRun))
        }
    }

    /** Bullet fonts (Symbol, Wingdings) are replaced by Unicode look-alikes, so the font goes too. */
    private fun labelStyle(run: RunProps): RunStyle {
        val style = run.toStyle().copy(hidden = false, underline = false)
        val font = style.font?.lowercase().orEmpty()
        return if (SYMBOL_FONTS.any { font.contains(it) }) style.copy(font = null) else style
    }

    private companion object {
        const val NO_LIST = "0"
        val SYMBOL_FONTS = listOf("symbol", "wingdings", "webdings")
    }
}
