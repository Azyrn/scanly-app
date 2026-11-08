package com.skeler.scanely.core.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.skeler.scanely.R
import com.skeler.scanely.core.common.UriNames
import com.skeler.scanely.core.ocr.paddle.PaddleOcrService
import com.skeler.scanely.settings.data.SettingsKeys
import com.skeler.scanely.settings.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TextOcrService"

@Singleton
class TextOcrService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val mlKit: MlKitOcrService,
    private val paddle: PaddleOcrService,
    private val pdfRendererHelper: PdfRendererHelper,
    private val settings: SettingsRepository
) {

    suspend fun engine(): TextOcrEngine =
        TextOcrEngine.fromId(settings.getString(SettingsKeys.TEXT_OCR_ENGINE).first())

    suspend fun recognize(bitmap: Bitmap): OcrResult = when (engine()) {
        TextOcrEngine.PADDLE -> paddle.recognize(bitmap).orFallback { mlKit.recognizeFromBitmap(bitmap) }
        TextOcrEngine.ML_KIT -> mlKit.recognizeFromBitmap(bitmap)
    }

    suspend fun recognizeFromUri(uri: Uri): OcrResult = when (engine()) {
        TextOcrEngine.PADDLE -> {
            val bitmap = loadBitmap(uri)
            if (bitmap == null) {
                OcrResult.Error(context.getString(R.string.ocr_err_load_image))
            } else {
                paddle.recognize(bitmap).orFallback { mlKit.recognizeFromBitmap(bitmap) }
                    .also { bitmap.recycle() }
            }
        }
        TextOcrEngine.ML_KIT -> mlKit.recognizeFromUri(uri)
    }

    /** [onPage] gets the zero-based index of each page as it finishes (whole-PDF runs only). */
    suspend fun recognizeFromPdf(
        pdfUri: Uri,
        pageIndex: Int? = null,
        onPage: (Int) -> Unit = {}
    ): OcrResult = when (engine()) {
        TextOcrEngine.PADDLE -> paddle.recognizeFromPdf(pdfUri, pageIndex, onPage)
            .orFallback { mlKit.recognizeFromPdf(pdfUri, pageIndex, onPage) }
        TextOcrEngine.ML_KIT -> mlKit.recognizeFromPdf(pdfUri, pageIndex, onPage)
    }

    /**
     * Reads every page of every PDF. [onProgress] counts pages across all the files (first with 0 done,
     * once the page total is known), so one long PDF shows progress too. It never goes backwards, even
     * when a Paddle failure makes ML Kit read the same file again.
     */
    suspend fun recognizeFromPdfs(
        uris: List<Uri>,
        onProgress: (BatchProgress) -> Unit = {}
    ): OcrResult {
        val counts = uris.map { pdfRendererHelper.getPageCount(it) }
        val total = counts.sum()
        var reported = 0
        onProgress(BatchProgress(0, total, pages = true))
        fun report(done: Int) {
            if (done > reported) {
                reported = done.coerceAtMost(total)
                onProgress(BatchProgress(reported, total, pages = true))
            }
        }

        if (uris.size == 1) {
            return recognizeFromPdf(uris.first()) { page -> report(page + 1) }
        }

        val batch = OcrBatchBuilder()
        var offset = 0
        uris.forEachIndexed { index, uri ->
            val start = offset
            when (val result = recognizeFromPdf(uri) { page -> report(start + page + 1) }) {
                is OcrResult.Success -> batch.add(labelFor(uri, index), result)
                is OcrResult.Error -> batch.addError(result.message)
                OcrResult.Empty -> Unit
            }
            offset += counts[index]
            report(offset)
        }
        return batch.build()
    }

    private fun labelFor(uri: Uri, index: Int): String =
        UriNames.displayName(context, uri) ?: "Document ${index + 1}"

    /** A broken/missing model must never cost the user their scan. */
    private suspend inline fun OcrResult.orFallback(block: () -> OcrResult): OcrResult {
        if (this !is OcrResult.Error) return this
        Log.w(TAG, "Paddle failed ($message), falling back to ML Kit")
        return block()
    }

    private suspend fun loadBitmap(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }
}
