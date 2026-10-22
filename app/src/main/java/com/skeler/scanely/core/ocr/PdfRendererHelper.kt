package com.skeler.scanely.core.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PdfRendererHelper"

// OCR reads lines at 48px; 3200px long-side keeps A4 body text native instead of upscaled.
private const val TARGET_LONG_SIDE = 3200
private const val PREVIEW_LONG_SIDE = 1400
private const val MAX_SCALE = 6f

@Singleton
class PdfRendererHelper @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    private fun scaleFor(pageWidth: Int, pageHeight: Int, longSide: Int): Float =
        (longSide.toFloat() / maxOf(pageWidth, pageHeight)).coerceAtMost(MAX_SCALE)

    private fun renderPage(page: PdfRenderer.Page, longSide: Int = TARGET_LONG_SIDE): Bitmap {
        val scale = scaleFor(page.width, page.height, longSide)
        val bitmap = Bitmap.createBitmap(
            (page.width * scale).toInt().coerceAtLeast(1),
            (page.height * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        // PDFs may be transparent.
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    suspend fun renderPage(pdfUri: Uri, pageIndex: Int = 0, preview: Boolean = false): Bitmap? = withContext(
        Dispatchers.Default
    ) {
        var parcelFileDescriptor: ParcelFileDescriptor? = null
        var pdfRenderer: PdfRenderer? = null

        try {
            parcelFileDescriptor = context.contentResolver.openFileDescriptor(pdfUri, "r")
                ?: return@withContext null

            pdfRenderer = PdfRenderer(parcelFileDescriptor)

            if (pageIndex < 0 || pageIndex >= pdfRenderer.pageCount) {
                Log.e(TAG, "Page index $pageIndex out of bounds (0-${pdfRenderer.pageCount - 1})")
                return@withContext null
            }

            val page = pdfRenderer.openPage(pageIndex)
            try {
                renderPage(page, if (preview) PREVIEW_LONG_SIDE else TARGET_LONG_SIDE)
            } finally {
                page.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to render PDF page", e)
            null
        } finally {
            pdfRenderer?.close()
            parcelFileDescriptor?.close()
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
        var pdfRenderer: PdfRenderer? = null

        try {
            parcelFileDescriptor = context.contentResolver.openFileDescriptor(pdfUri, "r")
                ?: return@withContext false

            pdfRenderer = PdfRenderer(parcelFileDescriptor)

            val wanted = (0 until pdfRenderer.pageCount).filter { pages == null || it in pages }
            for (i in wanted) {
                val bitmap = try {
                    val page = pdfRenderer.openPage(i)
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
            pdfRenderer?.close()
            parcelFileDescriptor?.close()
        }
    }

    suspend fun getPageCount(pdfUri: Uri): Int = withContext(Dispatchers.Default) {
        var parcelFileDescriptor: ParcelFileDescriptor? = null
        var pdfRenderer: PdfRenderer? = null

        try {
            parcelFileDescriptor = context.contentResolver.openFileDescriptor(pdfUri, "r")
                ?: return@withContext 0

            pdfRenderer = PdfRenderer(parcelFileDescriptor)
            pdfRenderer.pageCount
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get PDF page count", e)
            0
        } finally {
            pdfRenderer?.close()
            parcelFileDescriptor?.close()
        }
    }
}
