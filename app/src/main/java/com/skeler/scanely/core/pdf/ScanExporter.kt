package com.skeler.scanely.core.pdf

import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.skeler.scanely.core.ocr.TextBlockData
import com.skeler.scanely.core.text.DocumentJson
import com.skeler.scanely.core.text.MarkdownParser
import com.skeler.scanely.core.text.MathText
import com.skeler.scanely.core.text.MdBlock
import com.skeler.scanely.core.text.MdSpan
import com.skeler.scanely.core.text.TextTables
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** [pages] are only the rasterised head of the document; [pageCount] is what the file holds. */
data class PdfPreview(val pages: List<Bitmap>, val pageCount: Int)

object ScanExporter {

    private const val AUTHORITY_SUFFIX = ".fileprovider"
    private const val ALBUM = "Scanly"
    private const val JPEG_QUALITY = 95
    private const val TAG = "ScanExporter"

    const val PDF_MIME_TYPE = "application/pdf"
    const val WORD_MIME_TYPE =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val CSV_MIME_TYPE = "text/csv"
    const val MARKDOWN_MIME_TYPE = "text/markdown"
    const val JSON_MIME_TYPE = "application/json"

    /** Page separator emitted by the OCR services when scanning multi-page PDFs. */
    private val PAGE_MARKER = Regex("^-{3} Page (\\d+) -{3}$")

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val PAGE_MARGIN = 40
    private const val BODY_TEXT_SIZE = 12f
    private const val BODY_LINE_SPACING_EXTRA = 4f

    suspend fun exportPdf(
        context: Context,
        bitmaps: List<Bitmap>,
        baseName: String
    ): Uri = withContext(Dispatchers.IO) {
        exportToDownloads(context, "$baseName.pdf", PDF_MIME_TYPE) { out ->
            val document = PdfDocument()
            try {
                bitmaps.forEachIndexed { index, bitmap ->
                    val pageInfo = PdfDocument.PageInfo
                        .Builder(bitmap.width, bitmap.height, index + 1)
                        .create()
                    val page = document.startPage(pageInfo)
                    page.canvas.drawColor(Color.WHITE)
                    page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                    document.finishPage(page)
                }
                document.writeTo(out)
            } finally {
                document.close()
            }
        }
    }

    /** Publishes a PDF the editor has already written to the cache. */
    suspend fun exportPdfFile(
        context: Context,
        source: java.io.File,
        baseName: String
    ): Uri = exportFile(context, source, "$baseName.pdf", PDF_MIME_TYPE)

    /** Publishes a finished file of any type (PDF, Word, Excel, PowerPoint) as it is, under [fileName]. */
    suspend fun exportFile(
        context: Context,
        source: java.io.File,
        fileName: String,
        mimeType: String
    ): Uri = withContext(Dispatchers.IO) {
        exportToDownloads(context, fileName, mimeType) { out ->
            source.inputStream().use { it.copyTo(out) }
        }
    }

    /** .docx with one scan image per page — deliberately images, not uncertain OCR text. */
    suspend fun exportWord(
        context: Context,
        bitmaps: List<Bitmap>,
        baseName: String
    ): Uri = withContext(Dispatchers.IO) {
        exportToDownloads(context, "$baseName.docx", WORD_MIME_TYPE) { out ->
            ZipOutputStream(out.buffered()).use { zip ->
                zip.writeText("[Content_Types].xml", wordContentTypes())
                zip.writeText("_rels/.rels", packageRelationships())
                zip.writeText("word/document.xml", wordDocument(bitmaps))
                zip.writeText("word/_rels/document.xml.rels", wordRelationships(bitmaps.size))
                bitmaps.forEachIndexed { index, bitmap ->
                    zip.putNextEntry(ZipEntry("word/media/image${index + 1}.jpg"))
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, zip)) {
                        throw IOException("Couldn't encode scan ${index + 1}")
                    }
                    zip.closeEntry()
                }
            }
        }
    }

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
        exportToDownloads(context, "$baseName.pdf", PDF_MIME_TYPE) { out ->
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
                            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
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
        exportToDownloads(context, "$baseName.csv", CSV_MIME_TYPE) { out ->
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
        exportToDownloads(context, "$baseName.docx", WORD_MIME_TYPE) { out ->
            ZipOutputStream(out.buffered()).use { zip ->
                zip.writeText("[Content_Types].xml", wordContentTypes())
                zip.writeText("_rels/.rels", packageRelationships())
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
        exportToDownloads(context, "$baseName.md", MARKDOWN_MIME_TYPE) { out ->
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
        exportToDownloads(context, "$baseName.json", JSON_MIME_TYPE) { out ->
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

        if (blocks.isEmpty()) {
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
        } else {
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

        return JSONObject()
            .put("text", text)
            .put("pages", pages)
            .toString(2)
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
        try {
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

                canvas.save()
                canvas.translate(PAGE_MARGIN.toFloat(), PAGE_MARGIN.toFloat() - top)
                canvas.clipRect(
                    0f,
                    top.toFloat(),
                    contentWidth.toFloat(),
                    (top + contentHeight).toFloat()
                )
                layout.draw(canvas)
                canvas.restore()

                document.finishPage(page)
                line = end
                pageNumber++
            }
        } catch (t: Throwable) {
            document.close()
            throw t
        }
        return document
    }

    suspend fun saveImagesToGallery(
        context: Context,
        bitmaps: List<Bitmap>,
        baseName: String
    ): Int = withContext(Dispatchers.IO) {
        var saved = 0
        bitmaps.forEachIndexed { index, bitmap ->
            val name = if (bitmaps.size == 1) baseName else "${baseName}_${index + 1}"
            if (saveOneToGallery(context, bitmap, name)) saved++
        }
        saved
    }

    private fun saveOneToGallery(context: Context, bitmap: Bitmap, displayName: String): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/$ALBUM"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                // Pre-Q has no RELATIVE_PATH; the album folder is set through the file path.
                @Suppress("DEPRECATION")
                val album = java.io.File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    ALBUM
                )
                if (!album.exists() && !album.mkdirs()) return false
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, java.io.File(album, "$displayName.jpg").absolutePath)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        // A missing storage grant on pre-Q surfaces here as a SecurityException.
        val uri = try {
            resolver.insert(collection, values)
        } catch (e: SecurityException) {
            Log.e(TAG, "Gallery save refused for $displayName", e)
            null
        } ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            } ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Gallery save failed for $displayName, rolling back MediaStore row", e)
            resolver.delete(uri, null, null)
            false
        }
    }

    /** Writes durable scan exports to the user-visible Downloads/Scanly directory. */
    private fun exportToDownloads(
        context: Context,
        displayName: String,
        mimeType: String,
        write: (OutputStream) -> Unit
    ): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$ALBUM")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values
            ) ?: throw IOException("Couldn't create $displayName in Downloads/$ALBUM")
            try {
                resolver.openOutputStream(uri)?.use(write)
                    ?: throw IOException("Couldn't open $displayName for writing")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return uri
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
        }

        // Pre-Q public Downloads needs a WRITE_EXTERNAL_STORAGE grant we never ask for; without it
        // fall back to the app's shareable cache so export still produces an openable file.
        val directory = if (hasLegacyWritePermission(context)) {
            @Suppress("DEPRECATION")
            java.io.File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                ALBUM
            )
        } else {
            java.io.File(context.cacheDir, "scans")
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Couldn't create ${directory.absolutePath}")
        }
        val file = java.io.File(directory, displayName)
        try {
            file.outputStream().use(write)
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
        return FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
    }

    private fun hasLegacyWritePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

    /** Never throws; false if no viewer or launch rejected. */
    fun openPdf(context: Context, uri: Uri): Boolean {
        return openDocument(context, uri, PDF_MIME_TYPE, "Open PDF with…")
    }

    /** Never throws; false if no app can open the exported file. */
    fun openDocument(context: Context, uri: Uri, mimeType: String, chooserTitle: String): Boolean {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            clipData = ClipData.newRawUri("Export", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (!hasHandler(context, view)) {
            Log.w(TAG, "No activity can view $mimeType")
            return false
        }

        grantToResolvers(context, view, uri)

        // The bare VIEW intent first: the system raises its own "Open with" picker when more than
        // one app matches. ACTION_CHOOSER is not resolvable on every ROM — where the intent
        // resolver has been stripped it throws ActivityNotFoundException even though the VIEW
        // intent underneath resolves — so the chooser is the fallback, not the default.
        return context.launch(view) || context.launch(
            Intent.createChooser(view, chooserTitle).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    /** Never throws; false if no handler or launch rejected. */
    fun shareDocument(context: Context, uri: Uri, mimeType: String): Boolean {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Export", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        grantToResolvers(context, send, uri)
        if (context !is Activity) send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // Sharing genuinely wants the picker, so the chooser leads here — but it falls back to the
        // bare SEND intent for the ROMs where ACTION_CHOOSER cannot be resolved at all.
        val chooser = Intent.createChooser(send, "Share").apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return context.launch(chooser) || context.launch(send)
    }

    /**
     * [shareDocument] for several files at once. [mimeType] is the type they share, or a wildcard
     * such as "image/" + "*" when they differ. Never throws; false if empty or the launch is rejected.
     */
    fun shareDocuments(context: Context, uris: List<Uri>, mimeType: String): Boolean {
        if (uris.isEmpty()) return false
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mimeType
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            // Every uri goes in the clip too: the grant flag only covers what the clip names.
            clipData = ClipData.newRawUri("Export", uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        uris.forEach { grantToResolvers(context, send, it) }
        return launchChooserOrSend(context, send)
    }

    /** Launches [send] through the share chooser, falling back to the bare intent if that is rejected. */
    private fun launchChooserOrSend(context: Context, send: Intent): Boolean {
        if (context !is Activity) send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val chooser = Intent.createChooser(send, "Share").apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return context.launch(chooser) || context.launch(send)
    }

    private fun Context.launch(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        Log.e(TAG, "Unable to launch ${intent.action}", e)
        false
    }

    private fun hasHandler(context: Context, intent: Intent): Boolean =
        context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .isNotEmpty()

    private fun grantToResolvers(context: Context, intent: Intent, uri: Uri) {
        val resolvers = context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        for (info in resolvers) {
            runCatching {
                context.grantUriPermission(
                    info.activityInfo.packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    private fun ZipOutputStream.writeText(path: String, content: String) {
        putNextEntry(ZipEntry(path))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun wordContentTypes(): String = """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Default Extension="jpg" ContentType="image/jpeg"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""

    private fun packageRelationships(): String = """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

    private fun wordRelationships(pageCount: Int): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        repeat(pageCount) { index ->
            append(
                "<Relationship Id=\"rId${index + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image${index + 1}.jpg\"/>"
            )
        }
        append("</Relationships>")
    }

    private fun wordDocument(bitmaps: List<Bitmap>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append(
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>"
        )
        bitmaps.forEachIndexed { index, bitmap ->
            val (cx, cy) = wordImageExtent(bitmap)
            val id = index + 1
            append(
                "<w:p><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$id\" name=\"Scan $id\"/><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic><pic:nvPicPr><pic:cNvPr id=\"0\" name=\"Scan $id\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\"rId$id\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"
            )
            if (index != bitmaps.lastIndex) append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
        }
        append(
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"720\" w:right=\"720\" w:bottom=\"720\" w:left=\"720\"/></w:sectPr></w:body></w:document>"
        )
    }

    private fun wordTextDocument(text: String): String = buildString {
        append(WORD_TEXT_HEADER)
        text.lineSequence().forEach { line ->
            append("<w:p><w:r><w:t xml:space=\"preserve\">")
            append(escapeXml(line))
            append("</w:t></w:r></w:p>")
        }
        append(WORD_TEXT_FOOTER)
    }

    internal fun wordMarkdownDocument(blocks: List<MdBlock>): String = buildString {
        append(WORD_TEXT_HEADER)
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    val size = when (block.level) {
                        1 -> 36
                        2 -> 30
                        3 -> 26
                        else -> 24
                    }
                    append(wordParagraph(wordRuns(block.text, bold = true, size = size)))
                }

                is MdBlock.Paragraph ->
                    append(wordParagraph(wordLines(block.lines)))

                is MdBlock.Bullet -> {
                    val marker = when (block.checked) {
                        true -> "☑ "
                        false -> "☐ "
                        null -> "• "
                    }
                    append(
                        wordParagraph(
                            wordRun(marker) + wordRuns(block.text),
                            indent = wordIndent(block.indent)
                        )
                    )
                }

                is MdBlock.Numbered -> append(
                    wordParagraph(
                        wordRun(block.marker + " ", bold = true) + wordRuns(block.text),
                        indent = wordIndent(block.indent)
                    )
                )

                is MdBlock.Quote -> append(
                    wordParagraph(
                        wordLines(block.lines, italic = true),
                        indent = wordIndent(1),
                        properties = "<w:pBdr><w:left w:val=\"single\" w:sz=\"12\" w:space=\"6\" " +
                            "w:color=\"888888\"/></w:pBdr>"
                    )
                )

                is MdBlock.Code -> append(
                    wordParagraph(
                        block.text.lines().joinToString(WORD_LINE_BREAK) { wordRun(it, mono = true) },
                        properties = "<w:shd w:val=\"clear\" w:fill=\"F2F2F2\"/>"
                    )
                )

                is MdBlock.Table -> append(wordTable(block))

                is MdBlock.Equation -> append(
                    wordParagraph(
                        MathText.prettify(block.latex).lines()
                            .joinToString(WORD_LINE_BREAK) { wordRun(it, italic = true) },
                        properties = "<w:jc w:val=\"center\"/>"
                    )
                )

                is MdBlock.Image -> append(
                    wordParagraph(wordRun(block.label, italic = true))
                )

                MdBlock.Divider -> append(
                    wordParagraph(
                        "",
                        properties = "<w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" " +
                            "w:color=\"999999\"/></w:pBdr>"
                    )
                )
            }
        }
        append(WORD_TEXT_FOOTER)
    }

    private fun wordTable(table: MdBlock.Table): String = buildString {
        val fill = PLAIN_FILL_HEX
        append("<w:tbl><w:tblPr><w:tblW w:w=\"5000\" w:type=\"pct\"/><w:tblBorders>")
        listOf("top", "left", "bottom", "right", "insideH", "insideV").forEach { edge ->
            append("<w:$edge w:val=\"single\" w:sz=\"6\" w:color=\"555555\"/>")
        }
        append("</w:tblBorders><w:tblLayout w:type=\"autofit\"/></w:tblPr>")
        append(wordTableGrid(table))

        append("<w:tr>")
        table.header.forEach { cell ->
            append("<w:tc><w:tcPr><w:shd w:val=\"clear\" w:fill=\"$fill\"/></w:tcPr>")
            append(wordParagraph(wordRuns(cell, bold = true)))
            append("</w:tc>")
        }
        append("</w:tr>")

        table.rows.forEach { row ->
            append("<w:tr>")
            table.header.indices.forEach { column ->
                append("<w:tc>")
                append(wordParagraph(wordRuns(row.getOrElse(column) { "" })))
                append("</w:tc>")
            }
            append("</w:tr>")
        }
        // Word treats two adjacent tables as one without a paragraph between them.
        append("</w:tbl><w:p/>")
    }

    /**
     * Required: the schema for `w:tbl` is `(w:tblPr, w:tblGrid, rows…)`, and a reader that finds no
     * grid is entitled to drop the whole table. Android's Word viewer does exactly that — it drew
     * the headings around three tables that were silently gone.
     *
     * The widths are only a starting hint, proportional to the text each column holds; `tblW` at
     * 100% and the reader's own autofit settle the rest.
     */
    private fun wordTableGrid(table: MdBlock.Table): String = buildString {
        val rows = listOf(table.header) + table.rows
        val weights = table.header.indices.map { column ->
            rows.maxOf { MarkdownParser.plain(it.getOrElse(column) { "" }).length }.coerceIn(4, 40)
        }
        val total = weights.sum().coerceAtLeast(1)
        append("<w:tblGrid>")
        weights.forEach { append("<w:gridCol w:w=\"${WORD_CONTENT_TWIPS * it / total}\"/>") }
        append("</w:tblGrid>")
    }

    private fun wordParagraph(
        runs: String,
        indent: String = "",
        properties: String = ""
    ): String {
        val paragraphProperties = indent + properties
        val prefix = if (paragraphProperties.isEmpty()) "" else "<w:pPr>$paragraphProperties</w:pPr>"
        return "<w:p>$prefix$runs</w:p>"
    }

    private fun wordIndent(level: Int): String =
        if (level <= 0) "" else "<w:ind w:left=\"${level * 360}\"/>"

    private fun wordLines(lines: List<String>, italic: Boolean = false): String =
        lines.joinToString(WORD_LINE_BREAK) { wordRuns(it, italic = italic) }

    /** The line's inline emphasis, carried over as Word run properties. */
    private fun wordRuns(
        text: String,
        bold: Boolean = false,
        italic: Boolean = false,
        size: Int = 0
    ): String = MarkdownParser.parseInline(text).joinToString("") { span: MdSpan ->
        wordRun(
            text = span.text,
            bold = bold || span.bold,
            italic = italic || span.italic,
            strike = span.strike,
            underline = span.underline || span.link != null,
            mono = span.code,
            size = size
        )
    }

    private fun wordRun(
        text: String,
        bold: Boolean = false,
        italic: Boolean = false,
        strike: Boolean = false,
        underline: Boolean = false,
        mono: Boolean = false,
        size: Int = 0
    ): String = buildString {
        append("<w:r><w:rPr>")
        if (bold) append("<w:b/>")
        if (italic) append("<w:i/>")
        if (strike) append("<w:strike/>")
        if (underline) append("<w:u w:val=\"single\"/>")
        if (mono) append("<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\"/>")
        if (size > 0) append("<w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/>")
        append("</w:rPr><w:t xml:space=\"preserve\">")
        append(escapeXml(text))
        append("</w:t></w:r>")
    }

    private const val WORD_TEXT_HEADER =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"

    /**
     * `w:br` is a *run* element. Emitted as a direct child of `w:p` it is invalid, and readers drop
     * it — which is how three labelled lines exported as one run-on `Client: …Date: …Project: …`.
     */
    private const val WORD_LINE_BREAK = "<w:r><w:br/></w:r>"

    private const val PLAIN_FILL_HEX = "EEEEEE"

    /** Page width less both margins, from [WORD_TEXT_FOOTER]'s `w:pgSz` and `w:pgMar`. */
    private const val WORD_CONTENT_TWIPS = 11906 - 2 * 720

    private const val WORD_TEXT_FOOTER =
        "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
            "<w:pgMar w:top=\"720\" w:right=\"720\" w:bottom=\"720\" w:left=\"720\"/></w:sectPr></w:body></w:document>"

    private fun escapeXml(value: String): String = buildString(value.length) {
        value.forEach { character ->
            // Control characters are illegal in XML 1.0; Word rejects the whole file as corrupt.
            if (character < ' ' && character != '\t') return@forEach
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> character
                }
            )
        }
    }

    private fun wordImageExtent(bitmap: Bitmap): Pair<Long, Long> {
        val pageWidth = 5_943_600L // A4 width minus 0.5in margins, in EMUs.
        val pageHeight = 8_229_600L // A4 height minus 0.5in margins, in EMUs.
        return if (bitmap.width.toLong() * pageHeight > bitmap.height.toLong() * pageWidth) {
            pageWidth to (pageWidth * bitmap.height / bitmap.width)
        } else {
            (pageHeight * bitmap.width / bitmap.height) to pageHeight
        }
    }
}
