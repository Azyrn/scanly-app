package com.skeler.scanely.core.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.graphics.createBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPage
import io.legere.pdfiumandroid.PdfiumCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PdfRendererHelper"

// OCR reads lines at 48px; 3200px long-side keeps A4 body text native instead of upscaled.
private const val TARGET_LONG_SIDE = 3200
private const val PREVIEW_LONG_SIDE = 1400
private const val MAX_SCALE = 6f

/**
 * Renders PDF pages for OCR with the pdfium bundled in the app (the same one the PDF editor and
 * tools use), not the platform PdfRenderer: these are untrusted files, and Android 9–12 ship an
 * old system pdfium that only gets fixed with an OS update.
 */
@Singleton
class PdfRendererHelper @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    private val core by lazy { PdfiumCore(context) }

    private fun scaleFor(pageWidth: Int, pageHeight: Int, longSide: Int): Float =
        (longSide.toFloat() / maxOf(pageWidth, pageHeight)).coerceAtMost(MAX_SCALE)

    private fun renderPage(page: PdfPage, longSide: Int = TARGET_LONG_SIDE): Bitmap {
        // Points, rotation applied — the same sizes the platform PdfRenderer reported.
        val pageWidth = page.getPageWidthPoint()
        val pageHeight = page.getPageHeightPoint()
        val scale = scaleFor(pageWidth, pageHeight, longSide)
        val width = (pageWidth * scale).toInt().coerceAtLeast(1)
        val height = (pageHeight * scale).toInt().coerceAtLeast(1)
        val bitmap = createBitmap(width, height)
        // PDFs may be transparent.
        bitmap.eraseColor(Color.WHITE)
        // No annotations: the platform renderer this replaced drew page content only, and OCR
        // output should not change with the renderer.
        page.renderPageBitmap(bitmap, 0, 0, width, height, renderAnnot = false, textMask = false)
        return bitmap
    }

    private fun open(descriptor: ParcelFileDescriptor): PdfDocument = core.newDocument(descriptor)

    suspend fun renderPage(pdfUri: Uri, pageIndex: Int = 0, preview: Boolean = false): Bitmap? = withContext(
        Dispatchers.Default
    ) {
        var parcelFileDescriptor: ParcelFileDescriptor? = null
        var document: PdfDocument? = null

        try {
            parcelFileDescriptor = context.contentResolver.openFileDescriptor(pdfUri, "r")
                ?: return@withContext null

            document = open(parcelFileDescriptor)

            if (pageIndex < 0 || pageIndex >= document.getPageCount()) {
                Log.e(TAG, "Page index $pageIndex out of bounds (0-${document.getPageCount() - 1})")
                return@withContext null
            }

            val page = document.openPage(pageIndex) ?: return@withContext null
            try {
                renderPage(page, if (preview) PREVIEW_LONG_SIDE else TARGET_LONG_SIDE)
            } finally {
                page.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to render PDF page", e)
            null
        } finally {
            document?.close()
            runCatching { parcelFileDescriptor?.close() }
        }
    }

    /**
     * One page at a time so the high-res render stays within a single-bitmap ceiling. [onPage] owns
     * the bitmap.
     *
     * [pages] holds zero-based indices to render, or null for all of them. Skipping is done before
     * `openPage` rather than in the callback: rendering a page only to drop it costs the same 29 MB
     * bitmap as one that gets used.
     */
    suspend fun forEachPage(
        pdfUri: Uri,
        pages: Set<Int>? = null,
        onPage: suspend (index: Int, bitmap: Bitmap) -> Unit
    ): Boolean = withContext(Dispatchers.Default) {
        var parcelFileDescriptor: ParcelFileDescriptor? = null
        var document: PdfDocument? = null

        try {
            parcelFileDescriptor = context.contentResolver.openFileDescriptor(pdfUri, "r")
                ?: return@withContext false

            document = open(parcelFileDescriptor)

            val wanted = (0 until document.getPageCount()).filter { pages == null || it in pages }
            for (i in wanted) {
                val bitmap = try {
                    val page = document.openPage(i) ?: error("pdfium could not open page $i")
                    try {
                        renderPage(page)
                    } finally {
                        page.close()
                    }
                } catch (pageError: Exception) {
                    Log.e(TAG, "Failed to render page $i", pageError)
                    continue
                }
                onPage(i, bitmap)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to render PDF pages", e)
            false
        } finally {
            document?.close()
            runCatching { parcelFileDescriptor?.close() }
        }
    }

    suspend fun getPageCount(pdfUri: Uri): Int = withContext(Dispatchers.Default) {
        var parcelFileDescriptor: ParcelFileDescriptor? = null
        var document: PdfDocument? = null

        try {
            parcelFileDescriptor = context.contentResolver.openFileDescriptor(pdfUri, "r")
                ?: return@withContext 0

            document = open(parcelFileDescriptor)
            document.getPageCount()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get PDF page count", e)
            0
        } finally {
            document?.close()
            runCatching { parcelFileDescriptor?.close() }
        }
    }
}
