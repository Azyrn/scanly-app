package com.skeler.scanely.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** [pages] are only the rasterised head of the document; [pageCount] is what the file holds. */
data class PdfPreview(val pages: List<Bitmap>, val pageCount: Int)

object ScanExporter {

    private const val JPEG_QUALITY = 95
    private const val TAG = "ScanExporter"

    const val PDF_MIME_TYPE = "application/pdf"
    const val WORD_MIME_TYPE =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val CSV_MIME_TYPE = "text/csv"
    const val MARKDOWN_MIME_TYPE = "text/markdown"
    const val JSON_MIME_TYPE = "application/json"

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
                zip.writeText("[Content_Types].xml", WORD_CONTENT_TYPES)
                zip.writeText("_rels/.rels", PACKAGE_RELATIONSHIPS)
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
}
