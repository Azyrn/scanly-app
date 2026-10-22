package com.skeler.scanely.core.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.skeler.scanely.core.actions.ScanAction
import com.skeler.scanely.core.actions.ScanActionDetector
import com.skeler.scanely.core.barcode.BarcodeEngine
import com.skeler.scanely.core.barcode.ZxingBarcodeDecoder
import com.skeler.scanely.core.barcode.mlKitActions
import com.skeler.scanely.core.common.UriNames
import com.skeler.scanely.core.ocr.OcrBatchBuilder
import com.skeler.scanely.core.ocr.OcrResult
import com.skeler.scanely.core.ocr.TextBlockData
import com.skeler.scanely.core.ocr.TextOcrEngine
import com.skeler.scanely.core.ocr.TextOcrService
import com.skeler.scanely.settings.data.SettingsKeys
import com.skeler.scanely.settings.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private const val TAG = "UnifiedScanService"

@Singleton
class UnifiedScanService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val textOcrService: TextOcrService
) {
    private val textRecognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val barcodeScanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_AZTEC,
                Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_PDF417,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_CODE_93,
                Barcode.FORMAT_CODABAR,
                Barcode.FORMAT_ITF
            )
            .build()
    )

    private val zxingDecoder by lazy { ZxingBarcodeDecoder() }

    /** Codes and text from one image. An empty result retries with preprocessing. */
    suspend fun scanImage(uri: Uri): UnifiedScanResult = withContext(Dispatchers.IO) {
        val bitmap = loadBitmapFromUri(uri)
            ?: return@withContext UnifiedScanResult(
                textResult = OcrResult.Error("Failed to load image"),
                barcodeActions = emptyList(),
                textActions = emptyList()
            )

        try {
            val result = scanBitmap(bitmap)

            if (result.isEmpty) {
                Log.d(TAG, "Initial scan empty, retrying with preprocessing")
                val preprocessed = com.skeler.scanely.core.image.ImagePreprocessor.preprocessForOcr(bitmap)
                try {
                    val retryResult = scanBitmap(preprocessed)
                    if (!retryResult.isEmpty) {
                        Log.d(TAG, "Preprocessing helped - found content on retry")
                        return@withContext retryResult
                    }
                } finally {
                    if (preprocessed != bitmap) preprocessed.recycle()
                }
            }

            result
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Scans every picked image with the offline engine, one at a time so a single bitmap stays
     * the memory ceiling however many files were imported. [onProgress] reports files finished.
     */
    suspend fun scanImages(
        uris: List<Uri>,
        onProgress: (Int) -> Unit = {}
    ): UnifiedScanResult = withContext(Dispatchers.IO) {
        val batch = OcrBatchBuilder()
        val barcodeActions = mutableListOf<ScanAction>()
        val textActions = mutableListOf<ScanAction>()

        uris.forEachIndexed { index, uri ->
            val result = scanImage(uri)
            when (val text = result.textResult) {
                is OcrResult.Success -> batch.add(labelFor(uri, index), text)
                is OcrResult.Error -> batch.addError(text.message)
                else -> Unit
            }
            barcodeActions += result.barcodeActions
            textActions += result.textActions
            onProgress(index + 1)
        }

        UnifiedScanResult(
            textResult = batch.build(),
            barcodeActions = barcodeActions,
            textActions = textActions
        )
    }

    private fun labelFor(uri: Uri, index: Int): String =
        UriNames.displayName(context, uri) ?: "Image ${index + 1}"

    private suspend fun scanBitmap(bitmap: Bitmap): UnifiedScanResult =
        combineScan(detectBarcodes(bitmap)) {
            when (textOcrService.engine()) {
                TextOcrEngine.PADDLE -> textOcrService.recognize(bitmap)
                TextOcrEngine.ML_KIT -> recognizeText(InputImage.fromBitmap(bitmap, 0))
            }
        }

    /** Gallery picker path — barcodes only, no OCR noise. */
    suspend fun scanBarcodeOnly(uri: Uri): List<ScanAction> = withContext(Dispatchers.IO) {
        val bitmap = loadBitmapFromUri(uri) ?: return@withContext emptyList()

        try {
            detectBarcodes(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun recognizeText(inputImage: InputImage): OcrResult =
        suspendCancellableCoroutine { continuation ->
            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    if (visionText.text.isBlank()) {
                        continuation.resume(OcrResult.Empty)
                        return@addOnSuccessListener
                    }

                    val blocks = visionText.textBlocks.map { block ->
                        TextBlockData(
                            text = block.text,
                            boundingBoxLeft = block.boundingBox?.left ?: 0,
                            boundingBoxTop = block.boundingBox?.top ?: 0,
                            boundingBoxRight = block.boundingBox?.right ?: 0,
                            boundingBoxBottom = block.boundingBox?.bottom ?: 0
                        )
                    }

                    val allConfidences = visionText.textBlocks.flatMap { block ->
                        block.lines.flatMap { line ->
                            line.elements.mapNotNull { it.confidence }
                        }
                    }
                    val avgConfidence = if (allConfidences.isNotEmpty()) {
                        allConfidences.average().toFloat()
                    } else {
                        1.0f
                    }

                    continuation.resume(
                        OcrResult.Success(
                            text = visionText.text,
                            blocks = blocks,
                            confidence = avgConfidence
                        )
                    )
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Text recognition failed", e)
                    continuation.resume(OcrResult.Error(e.message ?: "Recognition failed"))
                }
        }

    private suspend fun detectBarcodes(bitmap: Bitmap): List<ScanAction> {
        val engineId = settingsRepository.getString(SettingsKeys.BARCODE_ENGINE).first()
        return when (BarcodeEngine.fromId(engineId)) {
            BarcodeEngine.ZXING_CPP -> try {
                zxingDecoder.decode(bitmap).flatMap { it.toActions() }
            } catch (e: Exception) {
                Log.e(TAG, "ZXing decode failed", e)
                emptyList()
            }
            BarcodeEngine.ML_KIT -> detectBarcodesMlKit(InputImage.fromBitmap(bitmap, 0))
        }
    }

    private suspend fun detectBarcodesMlKit(inputImage: InputImage): List<ScanAction> =
        suspendCancellableCoroutine { continuation ->
            barcodeScanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    continuation.resume(galleryActions(barcodes))
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Barcode detection failed", e)
                    continuation.resume(emptyList())
                }
        }

    companion object {
        private const val MAX_BITMAP_DIMENSION = 2048
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(inputStream, null, options)

                options.inSampleSize = calculateInSampleSize(
                    options.outWidth,
                    options.outHeight,
                    MAX_BITMAP_DIMENSION
                )
                options.inJustDecodeBounds = false

                // Re-open stream (first pass consumed it).
                context.contentResolver.openInputStream(uri)?.use { newStream ->
                    BitmapFactory.decodeStream(newStream, null, options)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bitmap", e)
            null
        }
    }

    private fun calculateInSampleSize(
        width: Int,
        height: Int,
        maxDimension: Int
    ): Int {
        var inSampleSize = 1

        if (width > maxDimension || height > maxDimension) {
            val halfWidth = width / 2
            val halfHeight = height / 2

            while ((halfWidth / inSampleSize) >= maxDimension ||
                (halfHeight / inSampleSize) >= maxDimension
            ) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    fun close() {
        textRecognizer.close()
        barcodeScanner.close()
    }
}

/**
 * One image's codes and text, combined. Split out of [UnifiedScanService.scanBitmap] so the rule
 * can be tested without ML Kit or a Bitmap.
 *
 * Text is read even when a code was found. An invoice with a payment QR, a ticket or a flyer is a
 * document that also carries a code; stopping at the code used to return it alone and drop the
 * text the user photographed it for.
 */
internal suspend fun combineScan(
    barcodes: List<ScanAction>,
    recognizeText: suspend () -> OcrResult
): UnifiedScanResult {
    val ocrResult = recognizeText()
    val textActions = if (ocrResult is OcrResult.Success) {
        ScanActionDetector.detectActions(ocrResult.text)
    } else {
        emptyList()
    }

    return UnifiedScanResult(
        textResult = ocrResult,
        barcodeActions = barcodes,
        textActions = textActions
    )
}

data class UnifiedScanResult(
    val textResult: OcrResult?,
    val barcodeActions: List<ScanAction>,
    val textActions: List<ScanAction>
) {
    val hasText: Boolean get() = textResult is OcrResult.Success
    val hasBarcodes: Boolean get() = barcodeActions.isNotEmpty()
    val hasSmartActions: Boolean get() = textActions.isNotEmpty()
    val isEmpty: Boolean get() = !hasText && !hasBarcodes
}

/** What a picked image offers for the codes found in it. */
internal fun galleryActions(barcodes: List<Barcode>): List<ScanAction> = mlKitActions(barcodes)
