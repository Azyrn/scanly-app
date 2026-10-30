package com.skeler.scanely.core.pdftools.pdf2office

import android.util.Log
import com.skeler.scanely.core.ocr.paddle.LayoutLabel
import com.skeler.scanely.core.ocr.paddle.PaddleOcrEngine
import com.skeler.scanely.core.pdfeditor.engine.PageGeometry
import com.skeler.scanely.core.pdfeditor.engine.PdfPageReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ModelTables"

/**
 * The bundled layout model's (PP-DocLayout-S) table regions on one page, in points from the top
 * left. Asked only when the table detector has a run the text alone can't settle
 * ([PageLayout.unconfirmedTables]), so most pages never render for it. Null when the page can't be
 * drawn or the model fails: the run then stays text, as it would without the model.
 */
@Singleton
class ModelTables @Inject constructor(
    private val reader: PdfPageReader,
    private val engine: PaddleOcrEngine
) {

    // Page sizes of the last document asked about: one lookup per conversion, not one per page.
    // Keyed by path, length and time, so a workspace file reused for another document isn't mistaken for it.
    private var sizes: Pair<String, List<PageGeometry>>? = null

    suspend fun regions(file: File, password: String, index: Int): List<LayoutBox>? = try {
        val widthPt = geometries(file, password)?.getOrNull(index)?.widthPt
        if (widthPt == null || widthPt <= 0f) {
            null
        } else {
            // Drawn at the model's own width: pdfium's anti-aliasing does the shrinking, so only a
            // small vertical squeeze is left to Bitmap.scale's plain bilinear. Shrinking a 2x render
            // that way instead scored irs_p17's index pages as tables (0.45-0.49 vs 0.10-0.18) and
            // set the phone apart from the host on 13 of 142 pages.
            val widthPx = MODEL_SIDE_PX
            reader.renderPage(file, index, widthPx, password)?.let { bitmap ->
                try {
                    val scale = widthPt / bitmap.width
                    withContext(Dispatchers.Default) { engine.detectLayout(bitmap) }
                        .filter { it.label == LayoutLabel.TABLE }
                        .map { LayoutBox(it.left * scale, it.top * scale, it.right * scale, it.bottom * scale) }
                } finally {
                    bitmap.recycle()
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "No layout regions for page $index", e)
        null
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "No memory for layout regions on page $index", e)
        null
    }

    private suspend fun geometries(file: File, password: String): List<PageGeometry>? {
        val key = "${file.path}:${file.length()}:${file.lastModified()}"
        sizes?.let { (cached, list) -> if (cached == key) return list }
        return reader.pageGeometries(file, password)?.also { sizes = key to it }
    }

    private companion object {
        const val MODEL_SIDE_PX = 480 // PaddleOcrEngine's LAYOUT_SIDE
    }
}
