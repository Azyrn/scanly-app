package com.skeler.scanely.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** [pages] are only the rasterised head of the document; [pageCount] is what the file holds. */
data class PdfPreview(val pages: List<Bitmap>, val pageCount: Int)

/**
 * Scan pages made one at a time at export quality, so an export holds one page rather than all of them.
 * [render] makes page i (blocking; called on an IO thread) and returns null when its file can't be read.
 */
class ScanPages(val count: Int, val render: (Int) -> Bitmap?)

/** A finished scan export; [unreadable] are the 1-based numbers of pages left out because they couldn't be read. */
data class ScanExport<T>(val value: T, val unreadable: List<Int>)

/**
 * Exports [count] pages strictly one at a time: [render] makes page i (null when its file can't be read),
 * [write] writes it, and it is [recycle]d before the next page is made, also when [write] throws. Returns the
 * 1-based numbers of the pages that could not be read, in order.
 */
internal inline fun <B : Any> exportOneByOne(
    count: Int,
    render: (Int) -> B?,
    recycle: (B) -> Unit,
    write: (index: Int, page: B) -> Unit
): List<Int> {
    val unreadable = mutableListOf<Int>()
    for (index in 0 until count) {
        val page = render(index)
        if (page == null) {
            unreadable += index + 1
            continue
        }
        try {
            write(index, page)
        } finally {
            recycle(page)
        }
    }
    return unreadable
}

object ScanExporter {

    private const val JPEG_QUALITY = 95
    private const val TAG = "ScanExporter"

    const val PDF_MIME_TYPE = "application/pdf"
    const val WORD_MIME_TYPE =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val CSV_MIME_TYPE = "text/csv"
    const val MARKDOWN_MIME_TYPE = "text/markdown"
    const val JSON_MIME_TYPE = "application/json"

    /**
     * The scan as a PDF, made one page at a time. [PdfDocument] still records every finished page until
     * writeTo, so the export peak is all pages once (not twice or three times, as when the pages were also
     * held by review).
     */
    suspend fun exportPdf(
        context: Context,
        pages: ScanPages,
        baseName: String
    ): ScanExport<Uri> = withContext(Dispatchers.IO) {
        var unreadable = emptyList<Int>()
        val uri = exportToDownloads(context, "$baseName.pdf", PDF_MIME_TYPE) { out ->
            val document = PdfDocument()
            try {
                var written = 0
                unreadable = exportOneByOne(pages.count, pages.render, ::recycleAfterPdfPage) { _, bitmap ->
                    val pageInfo = PdfDocument.PageInfo
                        .Builder(bitmap.width, bitmap.height, ++written)
                        .create()
                    val page = document.startPage(pageInfo)
                    page.canvas.drawColor(Color.WHITE)
                    page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                    document.finishPage(page)
                }
                if (written == 0) throw IOException("No scan page could be read")
                document.writeTo(out)
            } finally {
                document.close()
            }
        }
        ScanExport(uri, unreadable)
    }

    /**
     * The page's recorded picture keeps its own reference to the pixels: recycle() only drops the Java one from
     * Android 8 on, where pixels live in the native heap. Before that they are on the Java heap and the
     * garbage collector reclaims them, so the bitmap is just left to it.
     */
    private fun recycleAfterPdfPage(bitmap: Bitmap) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) bitmap.recycle()
    }

    /**
     * .docx with one scan image per page, made one page at a time: each page is held only as its JPEG until the
     * document (which needs every image's size first) is written.
     */
    suspend fun exportWord(
        context: Context,
        pages: ScanPages,
        baseName: String
    ): ScanExport<Uri> = withContext(Dispatchers.IO) {
        val encoded = mutableListOf<EncodedPage>()
        val unreadable = exportOneByOne(pages.count, pages.render, Bitmap::recycle) { index, bitmap ->
            val jpeg = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpeg)) {
                throw IOException("Couldn't encode scan ${index + 1}")
            }
            encoded += EncodedPage(bitmap.width, bitmap.height, jpeg.toByteArray())
        }
        if (encoded.isEmpty()) throw IOException("No scan page could be read")
        val uri = exportToDownloads(context, "$baseName.docx", WORD_MIME_TYPE) { out ->
            ZipOutputStream(out.buffered()).use { zip ->
                zip.writeText("[Content_Types].xml", WORD_CONTENT_TYPES)
                zip.writeText("_rels/.rels", PACKAGE_RELATIONSHIPS)
                zip.writeText("word/document.xml", wordDocument(encoded.map { it.width to it.height }))
                zip.writeText("word/_rels/document.xml.rels", wordRelationships(encoded.size))
                encoded.forEachIndexed { index, page ->
                    zip.putNextEntry(ZipEntry("word/media/image${index + 1}.jpg"))
                    zip.write(page.jpeg)
                    zip.closeEntry()
                }
            }
        }
        ScanExport(uri, unreadable)
    }

    /** Saves each page to the gallery, made one at a time; [ScanExport.value] is how many were saved. */
    suspend fun saveImagesToGallery(
        context: Context,
        pages: ScanPages,
        baseName: String
    ): ScanExport<Int> = withContext(Dispatchers.IO) {
        var saved = 0
        val unreadable = exportOneByOne(pages.count, pages.render, Bitmap::recycle) { index, bitmap ->
            val name = if (pages.count == 1) baseName else "${baseName}_${index + 1}"
            if (saveOneToGallery(context, bitmap, name)) saved++
        }
        ScanExport(saved, unreadable)
    }

    private class EncodedPage(val width: Int, val height: Int, val jpeg: ByteArray)

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
