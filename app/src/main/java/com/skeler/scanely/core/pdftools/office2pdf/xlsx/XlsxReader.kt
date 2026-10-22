package com.skeler.scanely.core.pdftools.office2pdf.xlsx

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** A package relationship: who it points at and what kind of part that is. */
internal data class Relationship(val id: String, val type: String, val target: String)

/**
 * Opens an .xlsx package and reads it in the order printing needs: the workbook's sheet list,
 * shared strings and styles up front, then one sheet at a time, so only one sheet's cells are ever
 * in memory. Every part is streamed through a pull parser behind [GuardedStream], so a crafted
 * file can't unpack into gigabytes or expand entities.
 *
 * Malformed packages throw [XlsxFormatException] (or the zip's own IOException); oversized ones
 * [XlsxTooLargeException]. [checkCancel] is called between rows so a cancelled run stops promptly.
 */
internal class XlsxReader private constructor(
    private val zip: ZipFile,
    private val cursorFactory: (InputStream) -> XmlCursor,
    private val checkCancel: () -> Unit
) : Closeable {
    private val entries: Map<String, ZipEntry>
    private val budget = ByteBudget(MAX_TOTAL_BYTES)
    private val open = mutableListOf<InputStream>()

    val notes = XlsxNotes()

    var date1904 = false
        private set

    lateinit var sheets: List<SheetRef>
        private set

    private lateinit var sharedStrings: List<String>
    private lateinit var styles: XlsxStyles
    private lateinit var formatter: NumberFormatter

    init {
        val all = zip.entries().toList()
        if (all.size > MAX_ENTRIES) throw XlsxTooLargeException("too many parts")
        entries = all.associateBy { it.name.trimStart('/').lowercase() }
    }

    private fun load() {
        val workbookPath = workbookPath()
        val workbookRels = relationships(workbookPath)
        val dir = workbookPath.substringBeforeLast('/', "")
        val theme = readTheme(workbookRels, dir)
        styles = relatedPart(workbookRels, dir, TYPE_STYLES)
            ?.use { XlsxStylesReader(XlsxColors(theme)).read(it.cursor) }
            ?: XlsxStyles(emptyList())
        sharedStrings = relatedPart(workbookRels, dir, TYPE_SHARED_STRINGS)
            ?.use { readSharedStrings(it.cursor) } ?: emptyList()
        val workbook = part(workbookPath) ?: throw XlsxFormatException("no workbook part")
        sheets = workbook.use { readWorkbook(it.cursor, workbookRels.associateBy { rel -> rel.id }, dir) }
        formatter = NumberFormatter(date1904)
    }

    /** Where the package's root relationships put the workbook, or its usual place. */
    private fun workbookPath(): String = relationships("")
        .firstOrNull { it.type.endsWith(OFFICE_DOCUMENT) }?.let { resolve("", it.target) }
        ?: DEFAULT_WORKBOOK

    private fun readTheme(workbookRels: List<Relationship>, dir: String): List<Int> =
        relatedPart(workbookRels, dir, TYPE_THEME)?.use { readThemeColors(it.cursor) } ?: XlsxColors.DEFAULT_THEME

    /** The first part of [type] among [rels] (relative to [dir]), opened; null when there is none. */
    private fun relatedPart(rels: List<Relationship>, dir: String, type: String): OpenPart? =
        rels.firstOrNull { it.type.endsWith(type) }?.let { part(resolve(dir, it.target)) }

    /** Visible worksheets only; hidden ones and chart sheets are counted into [notes] instead. */
    private fun readWorkbook(cursor: XmlCursor, rels: Map<String, Relationship>, dir: String): List<SheetRef> {
        val visible = mutableListOf<SheetRef>()
        while (true) {
            when (cursor.next()) {
                XmlCursor.START -> when (cursor.name) {
                    "workbookPr" -> date1904 = cursor.flag("date1904")
                    "sheet" -> sheetAt(cursor, rels, dir)?.let { visible += it }
                }
                XmlCursor.END_DOCUMENT -> return visible
            }
        }
    }

    /** The `<sheet>` at [cursor] when it's a visible worksheet; hidden and chart sheets go into [notes]. */
    private fun sheetAt(cursor: XmlCursor, rels: Map<String, Relationship>, dir: String): SheetRef? {
        val rel = cursor.attr("id")?.let(rels::get)
        val state = cursor.attr("state")
        when {
            rel == null -> Unit
            state == "hidden" || state == "veryHidden" -> notes.hiddenSheets++
            rel.type.endsWith(TYPE_CHARTSHEET) -> notes.charts++
            rel.type.endsWith(TYPE_WORKSHEET) ->
                return SheetRef(cursor.attr("name").orEmpty(), resolve(dir, rel.target))
        }
        return null
    }

    private fun readSharedStrings(cursor: XmlCursor): List<String> {
        val strings = ArrayList<String>()
        var chars = 0L
        while (true) {
            when (cursor.next()) {
                XmlCursor.START -> if (cursor.name == "si") {
                    val text = cursor.readRichText().take(MAX_CELL_CHARS)
                    chars += text.length
                    if (chars > MAX_SHARED_CHARS) throw XlsxTooLargeException("shared strings too large")
                    strings += text
                    if (strings.size % CANCEL_EVERY == 0) checkCancel()
                }
                XmlCursor.END_DOCUMENT -> return strings
            }
        }
    }

    fun readSheet(ref: SheetRef): XlsxSheet {
        val sheetPart = part(ref.path) ?: throw XlsxFormatException("missing sheet part")
        val reader = XlsxSheetReader(ref.name, sharedStrings, styles, formatter, notes, checkCancel)
        val sheet = sheetPart.use { reader.read(it.cursor) }
        reader.drawingId?.let { countDrawing(ref.path, it) }
        countRelatedParts(ref.path)
        return sheet
    }

    private fun countRelatedParts(sheetPath: String) {
        if (relationships(sheetPath).any { it.type.endsWith(TYPE_PIVOT_TABLE) }) notes.pivotTables++
    }

    /** Charts and pictures sit in a drawing part next to the sheet; they're counted, not drawn. */
    private fun countDrawing(sheetPath: String, drawingId: String) {
        val rel = relationships(sheetPath).firstOrNull { it.id == drawingId } ?: return
        val drawing = part(resolve(sheetPath.substringBeforeLast('/', ""), rel.target)) ?: return
        drawing.use { countDrawingContents(it.cursor) }
    }

    private fun countDrawingContents(cursor: XmlCursor) {
        while (true) {
            when (cursor.next()) {
                XmlCursor.START -> when (cursor.name) {
                    "chart" -> notes.charts++
                    "pic" -> notes.pictures++
                }
                XmlCursor.END_DOCUMENT -> break
            }
        }
    }

    /** The relationships of [partPath] ("" for the package itself). A missing rels part is no relationships. */
    private fun relationships(partPath: String): List<Relationship> {
        val dir = partPath.substringBeforeLast('/', "")
        val file = partPath.substringAfterLast('/')
        val relsPath = (if (dir.isEmpty()) "" else "$dir/") + "_rels/$file.rels"
        val opened = part(relsPath) ?: return emptyList()
        return opened.use {
            val list = mutableListOf<Relationship>()
            while (true) {
                when (it.cursor.next()) {
                    XmlCursor.START -> relationshipAt(it.cursor)?.let { rel -> list += rel }
                    XmlCursor.END_DOCUMENT -> break
                }
            }
            list
        }
    }

    private fun relationshipAt(cursor: XmlCursor): Relationship? {
        if (cursor.name != "Relationship" || cursor.attr("TargetMode") == "External") return null
        return Relationship(cursor.attr("Id").orEmpty(), cursor.attr("Type").orEmpty(), cursor.attr("Target").orEmpty())
    }

    private inner class OpenPart(private val stream: InputStream) : Closeable {
        val cursor: XmlCursor = cursorFactory(stream)

        override fun close() {
            open -= stream
            runCatching { stream.close() }
        }
    }

    private fun part(path: String): OpenPart? {
        val entry = entries[path.trimStart('/').lowercase()] ?: return null
        val stream = GuardedStream(zip.getInputStream(entry).buffered(), MAX_PART_BYTES, budget)
        open += stream
        return OpenPart(stream)
    }

    override fun close() {
        open.toList().forEach { runCatching { it.close() } }
        runCatching { zip.close() }
    }

    companion object {
        /** Any one part may unpack to this much XML. */
        const val MAX_PART_BYTES = 192L * 1024 * 1024

        /** And the whole workbook to this much, across every part read. */
        const val MAX_TOTAL_BYTES = 384L * 1024 * 1024

        /** Excel's own limit per cell; more than that is never printed anyway. */
        const val MAX_CELL_CHARS = 32_767

        private const val MAX_SHARED_CHARS = 24L * 1024 * 1024
        private const val MAX_ENTRIES = 20_000
        private const val CANCEL_EVERY = 4_096
        private const val DEFAULT_WORKBOOK = "xl/workbook.xml"
        private const val OFFICE_DOCUMENT = "/officeDocument"
        private const val TYPE_THEME = "/theme"
        private const val TYPE_STYLES = "/styles"
        private const val TYPE_SHARED_STRINGS = "/sharedStrings"
        private const val TYPE_WORKSHEET = "/worksheet"
        private const val TYPE_CHARTSHEET = "/chartsheet"
        private const val TYPE_PIVOT_TABLE = "/pivotTable"

        fun open(
            file: File,
            checkCancel: () -> Unit = {},
            cursorFactory: (InputStream) -> XmlCursor = ::PullXmlCursor
        ): XlsxReader {
            val reader = XlsxReader(ZipFile(file), cursorFactory, checkCancel)
            var loaded = false
            try {
                reader.load()
                loaded = true
            } finally {
                if (!loaded) reader.close()
            }
            return reader
        }

        /** A relationship target, relative to the source part's folder unless it starts with '/'. */
        fun resolve(baseDir: String, target: String): String {
            val joined = when {
                target.startsWith("/") -> target.drop(1)
                baseDir.isEmpty() -> target
                else -> "$baseDir/$target"
            }
            val segments = ArrayDeque<String>()
            joined.split('/').forEach { segment ->
                when (segment) {
                    "", "." -> Unit
                    ".." -> segments.removeLastOrNull()
                    else -> segments.addLast(segment)
                }
            }
            return segments.joinToString("/")
        }
    }
}
