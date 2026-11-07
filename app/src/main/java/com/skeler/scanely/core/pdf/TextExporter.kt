package com.skeler.scanely.core.pdf

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import com.skeler.scanely.core.ocr.TextBlockData
import com.skeler.scanely.core.pdf.markdown.MarkdownPdf
import com.skeler.scanely.core.text.DocumentJson
import com.skeler.scanely.core.text.MarkdownParser
import com.skeler.scanely.core.text.TextTables
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.ZipOutputStream

/** Page separator emitted by the OCR services when scanning multi-page PDFs. */
private val PAGE_MARKER = Regex("^-{3} Page (\\d+) -{3}$")

private const val PAGE_WIDTH = 595
private const val PAGE_HEIGHT = 842
private const val PAGE_MARGIN = 40
private const val BODY_TEXT_SIZE = 12f
private const val BODY_LINE_SPACING_EXTRA = 4f

/** Exports of recognised text: PDF, Word, CSV, Markdown, plain text and JSON. */
object TextExporter {

    /**
     * [asMarkdown] mirrors the Markdown preview — headings, emphasis, lists and table grids
     * laid out; otherwise the text is drawn exactly as the plain view shows it.
     */
    suspend fun exportTextPdf(
        context: Context,
        text: String,
        asMarkdown: Boolean,
        baseName: String
    ): Uri = withContext(Dispatchers.IO) {
        exportToDownloads(context, "$baseName.pdf", ScanExporter.PDF_MIME_TYPE) { out ->
            val document =
                if (asMarkdown) MarkdownPdf.render(text) else buildTextPdfDocument(text)
            try {
                document.writeTo(out)
            } finally {
                document.close()
            }
        }
    }

    /**
     * The pages [exportTextPdf] would write, rasterised for on-screen reading. Rendering the real
     * document instead of re-laying it out in Compose is what keeps the preview and the file from
     * ever disagreeing.
     */
    suspend fun renderTextPdfPages(
        context: Context,
        text: String,
        asMarkdown: Boolean,
        pageWidthPx: Int,
        maxPages: Int
    ): PdfPreview = withContext(Dispatchers.IO) {
        val file = java.io.File.createTempFile("preview", ".pdf", context.cacheDir)
        try {
            val document =
                if (asMarkdown) MarkdownPdf.render(text) else buildTextPdfDocument(text)
            try {
                file.outputStream().use(document::writeTo)
            } finally {
                document.close()
            }

            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    val shown = minOf(renderer.pageCount, maxPages)
                    val pages = (0 until shown).map { index ->
                        renderer.openPage(index).use { page ->
                            val width = pageWidthPx.coerceAtLeast(1)
                            val height = (width.toLong() * page.height / page.width)
                                .toInt()
                                .coerceAtLeast(1)
                            createBitmap(width, height).also {
                                it.eraseColor(Color.WHITE)
                                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                    PdfPreview(pages = pages, pageCount = renderer.pageCount)
                }
            }
        } finally {
            file.delete()
        }
    }

    /** [rows] are the table rows read out of the preview; a blank row separates two tables. */
    suspend fun exportTextCsv(
        context: Context,
        rows: List<List<String>>,
        baseName: String
    ): Uri = withContext(Dispatchers.IO) {
        exportToDownloads(context, "$baseName.csv", ScanExporter.CSV_MIME_TYPE) { out ->
            out.bufferedWriter().use { it.write(TextTables.toCsv(rows)) }
        }
    }

    /** Word keeps the preview's formatting when it is Markdown, and its lines when it is not. */
    suspend fun exportTextWord(
        context: Context,
        text: String,
        asMarkdown: Boolean,
        baseName: String
    ): Uri = withContext(Dispatchers.IO) {
        val document = if (asMarkdown) {
            wordMarkdownDocument(MarkdownParser.parse(text))
        } else {
            wordTextDocument(text)
        }
        exportToDownloads(context, "$baseName.docx", ScanExporter.WORD_MIME_TYPE) { out ->
            ZipOutputStream(out.buffered()).use { zip ->
                zip.writeText("[Content_Types].xml", WORD_CONTENT_TYPES)
                zip.writeText("_rels/.rels", PACKAGE_RELATIONSHIPS)
                zip.writeText("word/document.xml", document)
                zip.writeText("word/_rels/document.xml.rels", wordRelationships(0))
            }
        }
    }

    /** The preview's own text: its Markdown source, or its plain text left as plain text. */
    suspend fun exportTextMarkdown(
        context: Context,
        text: String,
        baseName: String
    ): Uri = withContext(Dispatchers.IO) {
        exportToDownloads(context, "$baseName.md", ScanExporter.MARKDOWN_MIME_TYPE) { out ->
            out.bufferedWriter().use { it.write(text) }
        }
    }

    /** Any plain-text format — a transcript's `.txt`, `.srt` or `.vtt` — into Downloads/Scanly. */
    suspend fun exportPlainText(
        context: Context,
        text: String,
        fileName: String,
        mimeType: String
    ): Uri = withContext(Dispatchers.IO) {
        exportToDownloads(context, fileName, mimeType) { out ->
            out.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        }
    }

    /**
     * PP-OCR gives us lines, not layout, so this mirrors PaddleOCR's own `save_to_json` shape:
     * per-line text, box and confidence — no headings, tables or reading structure.
     */
    suspend fun exportTextJson(
        context: Context,
        text: String,
        blocks: List<TextBlockData>,
        baseName: String,
        isMarkdown: Boolean = false
    ): Uri = withContext(Dispatchers.IO) {
        exportToDownloads(context, "$baseName.json", ScanExporter.JSON_MIME_TYPE) { out ->
            out.bufferedWriter().use { it.write(buildJson(text, blocks, isMarkdown)) }
        }
    }

    internal fun buildJson(
        text: String,
        blocks: List<TextBlockData>,
        isMarkdown: Boolean = false
    ): String {
        // Markdown provenance means real structure: per-element JSON beats per-line JSON.
        if (blocks.isEmpty() && isMarkdown) return DocumentJson.fromMarkdown(text)

        val pages = JSONArray()

        if (blocks.isEmpty()) textPages(text, pages) else blockPages(blocks, pages)

        return JSONObject()
            .put("text", text)
            .put("pages", pages)
            .toString(2)
    }

    /** Pages split at the OCR page markers, one entry per non-blank line. */
}

private fun textPages(text: String, pages: JSONArray) {
    splitPages(text).forEach { (number, lines) ->
        pages.put(
            JSONObject()
                .put("page", number)
                .put(
                    "lines",
                    JSONArray(
                        lines.filter { it.isNotBlank() }.map {
                            JSONObject().put("text", it)
                        }
                    )
                )
        )
    }
}

/** Pages from the recognised blocks, each line with its confidence and box. */
private fun blockPages(blocks: List<TextBlockData>, pages: JSONArray) {
    blocks.groupBy { it.page }.toSortedMap().forEach { (number, pageBlocks) ->
        val lines = JSONArray()
        pageBlocks.forEach { block ->
            lines.put(
                JSONObject()
                    .put("text", block.text)
                    .put("confidence", block.confidence.toDouble())
                    .put(
                        "box",
                        JSONArray(
                            listOf(
                                block.boundingBoxLeft,
                                block.boundingBoxTop,
                                block.boundingBoxRight,
                                block.boundingBoxBottom
                            )
                        )
                    )
            )
        }
        pages.put(JSONObject().put("page", number).put("lines", lines))
    }
}

/** Splits OCR output on its page markers; text without markers is a single page. */
private fun splitPages(text: String): List<Pair<Int, List<String>>> {
    val pages = mutableListOf<Pair<Int, MutableList<String>>>()
    var current = 1 to mutableListOf<String>()

    text.lines().forEach { line ->
        val marker = PAGE_MARKER.matchEntire(line.trim())
        if (marker == null) {
            current.second.add(line.trim())
        } else {
            pages.add(current)
            current = (marker.groupValues[1].toIntOrNull() ?: pages.size + 1) to mutableListOf()
        }
    }
    pages.add(current)

    return pages
        .filter { (_, lines) -> lines.any { it.isNotBlank() } }
        .map { (number, lines) -> number to lines.dropWhile(String::isBlank).dropLastWhile(String::isBlank) }
}

/** Caller owns and must close the returned document. */
private fun buildTextPdfDocument(text: String): PdfDocument {
    val contentWidth = PAGE_WIDTH - 2 * PAGE_MARGIN
    val contentHeight = PAGE_HEIGHT - 2 * PAGE_MARGIN

    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = BODY_TEXT_SIZE
        typeface = Typeface.SANS_SERIF
    }

    val body = text.ifBlank { " " }
    val layout = StaticLayout.Builder
        .obtain(body, 0, body.length, paint, contentWidth)
        .setLineSpacing(BODY_LINE_SPACING_EXTRA, 1f)
        .setIncludePad(false)
        .build()

    val document = PdfDocument()
    // Closed on any failure, which still propagates; the caller owns it only once built.
    var built = false
    try {
        drawTextPages(document, layout, contentWidth, contentHeight)
        built = true
    } finally {
        if (!built) document.close()
    }
    return document
}

/** Lays [layout] onto as many pages as it needs, breaking between lines. */
private fun drawTextPages(document: PdfDocument, layout: StaticLayout, contentWidth: Int, contentHeight: Int) {
    var line = 0
    var pageNumber = 1
    val lineCount = layout.lineCount
    while (line < lineCount) {
        val pageInfo = PdfDocument.PageInfo
            .Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber)
            .create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas
        canvas.drawColor(Color.WHITE)

        val top = layout.getLineTop(line)
        var end = line
        while (end < lineCount &&
            layout.getLineBottom(end) - top <= contentHeight
            ) end++
        // Oversized single line must still advance.
        if (end == line) end = line + 1

        canvas.withTranslation(PAGE_MARGIN.toFloat(), PAGE_MARGIN.toFloat() - top) {
            clipRect(
                0f,
                top.toFloat(),
                contentWidth.toFloat(),
                (top + contentHeight).toFloat()
            )
            layout.draw(this)
        }

        document.finishPage(page)
        line = end
        pageNumber++
    }
}
