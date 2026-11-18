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

    /** Inside a table (its cells are being read). */
    val inTable: Boolean get() = tableStyle != null

    val fields = FieldState()
    private val sectionReader = SectionReader(notes)
    private val inlines = DocxInlineReader(this)
    private val tables = DocxTableReader(this)

    fun read(): DocxDocument {
        val documentPath = mainDocumentPath(zip, newParser)
        if (zip.getEntry(documentPath) == null) throw damaged()
        rels = relationshipsOf(zip, documentPath, newParser)
        theme = auxiliary("/theme") { parseTheme(it) } ?: theme
        styles = auxiliary("/styles") { parseStyles(it, theme) } ?: styles
        numbering = auxiliary("/numbering") { parseNumbering(it, theme) } ?: numbering
        val sections = inheritHeaders(
            parsePart(zip, documentPath, DOCUMENT_CAP, newParser, ::parseDocument) ?: throw damaged()
        )
        val settings = auxiliary("/settings") { parseSettings(it) } ?: DocxSettings()
        val footnotes = auxiliary("/footnotes") { parseFootnotes(it) } ?: Footnotes()
        return DocxDocument(
            sections,
            notes,
            headerParts(sections),
            settings.evenAndOddHeaders,
            settings.shrinkSpaces,
            footnotes.notes,
            footnotes.separator
        )
    }

    /** Footnotes are numbered in the order the body refers to them (w:footnoteReference without custom mark). */
    var footnoteCount = 0

    private class Footnotes(
        val notes: Map<String, List<DocxBlock>> = emptyMap(),
        val separator: List<DocxBlock> = emptyList()
    )

    /** footnotes.xml: each w:footnote's blocks by id; the separator one apart. */
    private fun parseFootnotes(parser: XmlPullParser): Footnotes {
        val notes = HashMap<String, List<DocxBlock>>()
        var separator = emptyList<DocxBlock>()
        parser.children { name ->
            if (name != "footnote" || !parser.isW()) {
                parser.skip()
                return@children
            }
            val type = parser.attr("type")
            val id = parser.attr("id")
            val blocks = ArrayList<DocxBlock>()
            readBlocks(parser, blocks, null)
            when {
                type == "separator" -> separator = blocks
                type == null || type == "normal" -> if (id != null) notes[id] = blocks
            }
        }
        return Footnotes(notes, separator)
    }

    /** Every header and footer part a section uses, read like the body; PAGE fields stay live in them. */
    private fun headerParts(sections: List<DocxSection>): Map<String, List<DocxBlock>> {
        val ids = sections.flatMap { it.page.headers.values + it.page.footers.values }.toSet()
        val parts = HashMap<String, List<DocxBlock>>()
        fields.livePages = true
        try {
            for (id in ids) {
                val target = rels[id]?.takeUnless { it.external }?.target ?: continue
                val blocks = try {
                    parsePart(zip, target, PART_CAP, newParser) { parser ->
                        ArrayList<DocxBlock>().also { readBlocks(parser, it, null) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (ignored: Exception) {
                    // One unreadable header is a header left out, not a failed document.
                    notes.headersFooters = true
                    null
                }
                if (blocks != null) parts[id] = blocks
            }
        } finally {
            fields.livePages = false
        }
        return parts
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
 *
 * With [livePages] on (header and footer parts), a PAGE field's result is the page it is drawn on,
 * not the cached digits: [pageResult] is true while inside such a result.
 */
internal class FieldState {
    private class Field(
        var inResult: Boolean = false,
        val instruction: StringBuilder = StringBuilder(),
        var shown: Boolean = false
    )

    private val stack = ArrayList<Field>()

    var livePages = false

    /** True while inside an instruction, where run text must be dropped. */
    val inInstruction: Boolean get() = stack.any { !it.inResult }

    /** Inside the result of a PAGE field that is drawn live ([livePages]). */
    val pageResult: Boolean
        get() = livePages && stack.lastOrNull()?.let { it.inResult && isPage(it.instruction) } == true

    fun mark(type: String?) {
        when (type) {
            "begin" -> if (stack.size < MAX_NESTING) stack += Field()
            "separate" -> stack.lastOrNull()?.inResult = true
            "end" -> stack.removeLastOrNull()
        }
    }

    /** True the first time a live PAGE result asks: the field shows its number once, whatever its cached runs. */
    fun claimPage(): Boolean {
        val field = stack.lastOrNull() ?: return false
        return !field.shown.also { field.shown = true }
    }

    /** Text of a w:instrText inside the current field's instruction. */
    fun instruction(text: String) {
        val field = stack.lastOrNull()?.takeIf { !it.inResult } ?: return
        if (field.instruction.length < MAX_INSTRUCTION) field.instruction.append(text)
    }

    companion object {
        private const val MAX_NESTING = 32
        private const val MAX_INSTRUCTION = 256

        /** `PAGE`, `PAGE \* Arabic \* MERGEFORMAT`; not PAGEREF or NUMPAGES. */
        fun isPage(instruction: CharSequence): Boolean =
            instruction.trim().split(' ', '\t').firstOrNull()?.equals("PAGE", ignoreCase = true) == true
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
    private val pendingText = StringBuilder()
    private var pendingStyle: RunStyle? = null
    private var segments = 0

    val context = InlineContext(
        runBase = { resolved.runBase },
        notes = session.notes,
        emit = ::emit,
        pageBreak = ::pageBreak
    )

    /**
     * A paragraph that ends with its page break keeps its mark on the page before the break, as Word
     * (2013 and later) shows it: nothing of it goes to the next page. Measured on un_en_c5_79_9, where
     * each annex page starts after a paragraph holding only a page break: Word's heading is at the
     * top margin; an empty line there put ours 18 pt lower and spilled two pages.
     */
    fun finish() {
        flushText()
        if (segments > 0 && segment.isEmpty()) return
        flush()
    }

    private fun emit(inline: DocxInline) {
        if (inline is DocxText) {
            if (pendingStyle != inline.style) flushText()
            pendingStyle = inline.style
            pendingText.append(inline.text)
        } else {
            flushText()
            segment += inline
        }
    }

    private fun flushText() {
        val style = pendingStyle ?: return
        segment += DocxText(pendingText.toString(), style)
        pendingText.setLength(0)
        pendingStyle = null
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
        flushText()
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
