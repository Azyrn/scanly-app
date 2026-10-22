package com.skeler.scanely.core.pdfeditor.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.ui.geometry.Rect
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legere.pdfiumandroid.PdfiumCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

private const val TAG = "PdfPageReader"

/** Document info dictionary, as shown before a metadata strip so the user sees what goes. */
data class PdfMetadata(
    val title: String,
    val author: String,
    val subject: String,
    val keywords: String,
    val creator: String,
    val producer: String,
    val creationDate: String,
    val modifiedDate: String
) {
    val entries: List<Pair<String, String>>
        get() = listOf(
            "Title" to title,
            "Author" to author,
            "Subject" to subject,
            "Keywords" to keywords,
            "Creator" to creator,
            "Producer" to producer,
            "Created" to creationDate,
            "Modified" to modifiedDate
        ).filter { it.second.isNotBlank() }
}

/**
 * Stops a pathological page stalling the editor. Not a judgement about which pages deserve text
 * tools: refusing a page silently disables retyping and text-snapped highlighting.
 *
 * Sized against real documents — a dense 9 pt A4 page is about five thousand characters, a legal
 * page with footnotes eight. Two native calls at this ceiling cost a few hundred ms, once per page.
 */
private const val MAX_SNAPSHOT_CHARS = 20_000

/**
 * The read half of the editor: page geometry, rasterisation and the text layer.
 *
 * Separate from the app's existing `PdfRendererHelper`, which stays on the platform PdfRenderer
 * for the OCR path. The editor needs pdfium specifically, because only it exposes per-character
 * boxes and font sizes — the things text-anchored highlighting and cover-and-retype are built on.
 */
@Singleton
class PdfPageReader @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    private val core by lazy { PdfiumCore(context) }

    suspend fun pageCount(file: File, password: String = ""): Int? =
        withDocument(file, password) { it.getPageCount() }

    suspend fun pageGeometries(file: File, password: String = ""): List<PageGeometry>? =
        withDocument(file, password) { document ->
            (0 until document.getPageCount()).map { index ->
                document.openPage(index)?.use { page ->
                    PageGeometry(
                        page.getPageWidthPoint().toFloat(),
                        page.getPageHeightPoint().toFloat()
                    )
                } ?: return@withDocument null
            }
        }

    /** [targetWidthPx] drives the render scale; height follows the page's aspect ratio. */
    suspend fun renderPage(
        file: File,
        pageIndex: Int,
        targetWidthPx: Int,
        password: String = ""
    ): Bitmap? = withDocument(file, password) { document ->
        if (pageIndex !in 0 until document.getPageCount()) return@withDocument null

        document.openPage(pageIndex)?.use { page ->
            val widthPt = page.getPageWidthPoint().toFloat().coerceAtLeast(1f)
            val heightPt = page.getPageHeightPoint().toFloat().coerceAtLeast(1f)
            val width = targetWidthPx.coerceAtLeast(1)
            val height = (width * heightPt / widthPt).roundToInt().coerceAtLeast(1)

            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                // Pages are transparent where nothing is drawn; without this, dark theme shows
                // through the paper.
                eraseColor(Color.WHITE)
                page.renderPageBitmap(
                    this,
                    0,
                    0,
                    width,
                    height,
                    renderAnnot = true,
                    // The mask path is for text-only renders and drops text anti-aliasing.
                    textMask = false
                )
            }
        }
    }

    /**
     * Opens the text layer for one page. The layer and its native handles are closed when [block]
     * returns, so nothing derived from it may outlive the call — copy out rects and strings.
     */
    suspend fun <T> withTextLayer(
        file: File,
        pageIndex: Int,
        password: String = "",
        block: (PdfTextLayer) -> T
    ): T? = withDocument(file, password) { document ->
        if (pageIndex !in 0 until document.getPageCount()) return@withDocument null

        document.openPage(pageIndex)?.use { page ->
            page.openTextPage().use { textPage ->
                block(PdfTextLayer(textPage, page.getPageHeightPoint().toFloat()))
            }
        }
    }

    suspend fun documentMeta(file: File, password: String = ""): PdfMetadata? =
        withDocument(file, password) { document ->
            document.getDocumentMeta().let {
                PdfMetadata(
                    title = it.title.orEmpty(),
                    author = it.author.orEmpty(),
                    subject = it.subject.orEmpty(),
                    keywords = it.keywords.orEmpty(),
                    creator = it.creator.orEmpty(),
                    producer = it.producer.orEmpty(),
                    creationDate = it.creationDate.orEmpty(),
                    modifiedDate = it.modDate.orEmpty()
                )
            }
        }

    /**
     * Copies the whole text layer out in one pass. Null on a page with no text — a photographed
     * page — which is the signal to fall back to free-form marking.
     */
    suspend fun textSnapshot(
        file: File,
        pageIndex: Int,
        password: String = ""
    ): PageTextSnapshot? = withTextLayer(file, pageIndex, password) { layer ->
        val count = layer.charCount
        if (count <= 0 || count > MAX_SNAPSHOT_CHARS) return@withTextLayer null

        val boxes = ArrayList<Rect>(count)
        val sizes = FloatArray(count)
        for (index in 0 until count) {
            boxes += layer.charBox(index) ?: Rect.Zero
            sizes[index] = layer.fontSizeAt(index)
        }
        PageTextSnapshot(boxes, sizes, layer.textIn(0..count - 1))
    }

    /**
     * The page's text alone, without the per-character geometry [textSnapshot] builds. Null on a
     * page with no text layer — a scanned image — which only OCR can read.
     */
    suspend fun plainText(file: File, pageIndex: Int, password: String = ""): String? =
        withTextLayer(file, pageIndex, password) { layer ->
            val count = layer.charCount
            if (count <= 0) null else layer.textIn(0..count - 1)
        }

    private suspend fun <T> withDocument(
        file: File,
        password: String,
        block: (io.legere.pdfiumandroid.PdfDocument) -> T?
    ): T? = withContext(Dispatchers.IO) {
        var descriptor: ParcelFileDescriptor? = null
        try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val document = if (password.isEmpty()) {
                core.newDocument(descriptor)
            } else {
                core.newDocument(descriptor, password)
            }
            document.use(block)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open ${file.name}", e)
            null
        } finally {
            runCatching { descriptor?.close() }
        }
    }
}
