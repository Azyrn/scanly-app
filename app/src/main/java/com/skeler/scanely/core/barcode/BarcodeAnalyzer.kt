package com.skeler.scanely.core.barcode

import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.skeler.scanely.core.actions.ScanAction

private const val TAG = "BarcodeAnalyzer"

// Stability gate: frames a barcode must repeat before accept.
private const val REQUIRED_CONSECUTIVE_MATCHES = 2

class BarcodeAnalyzer(
    private val engine: BarcodeEngine = BarcodeEngine.ML_KIT,
    private val onBarcodeDetected: (List<ScanAction>) -> Unit
) : ImageAnalysis.Analyzer {

    // Set once CameraControl exists; true if zoom applied. ML Kit only — ZXing has no zoom hints.
    var onZoomSuggested: ((Float) -> Boolean)? = null

    private val scanner: BarcodeScanner? = if (engine == BarcodeEngine.ML_KIT) {
        BarcodeScanning.getClient(
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
                    Barcode.FORMAT_CODE_39
                )
                .setZoomSuggestionOptions(
                    ZoomSuggestionOptions.Builder { zoomRatio ->
                        onZoomSuggested?.invoke(zoomRatio) ?: false
                    }.build()
                )
                .build()
        )
    } else {
        null
    }

    private val zxingDecoder by lazy { ZxingBarcodeDecoder() }

    private var lastProcessedTime = 0L
    private val throttleInterval = 100L

    private var lastRawValues: Set<String> = emptySet()
    private var consecutiveMatches = 0

    override fun analyze(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessedTime < throttleInterval) {
            imageProxy.close()
            return
        }

        when (engine) {
            BarcodeEngine.ZXING_CPP -> analyzeWithZxing(imageProxy, currentTime)
            BarcodeEngine.ML_KIT -> analyzeWithMlKit(imageProxy, currentTime)
        }
    }

    private fun analyzeWithZxing(imageProxy: ImageProxy, currentTime: Long) {
        val decoded = try {
            zxingDecoder.decode(imageProxy)
        } catch (e: Exception) {
            Log.e(TAG, "ZXing decode failed", e)
            emptyList()
        } finally {
            imageProxy.close()
        }

        handleStableDetection(decoded.map { it.text }.toSet(), currentTime) {
            decoded.flatMap { it.toActions() }.distinct()
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyzeWithMlKit(imageProxy: ImageProxy, currentTime: Long) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        scanner?.process(inputImage)
            ?.addOnSuccessListener { barcodes ->
                handleStableDetection(barcodes.mapNotNull { it.rawValue }.toSet(), currentTime) {
                    cameraActions(barcodes)
                }
            }
            ?.addOnFailureListener { e ->
                Log.e(TAG, "Barcode scanning failed", e)
            }
            ?.addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun handleStableDetection(
        rawValues: Set<String>,
        currentTime: Long,
        buildActions: () -> List<ScanAction>
    ) {
        if (rawValues.isEmpty()) {
            lastRawValues = emptySet()
            consecutiveMatches = 0
            return
        }

        if (rawValues == lastRawValues) {
            consecutiveMatches++
        } else {
            lastRawValues = rawValues
            consecutiveMatches = 1
        }

        if (consecutiveMatches >= REQUIRED_CONSECUTIVE_MATCHES) {
            lastProcessedTime = currentTime
            val actions = buildActions()
            if (actions.isNotEmpty()) {
                Log.d(TAG, "Confirmed ${actions.size} actions from ${rawValues.size} barcodes")
                onBarcodeDetected(actions)
            }
        }
    }

    fun close() {
        scanner?.close()
    }
}

/** What the live camera offers for the codes in one frame. */
internal fun cameraActions(barcodes: List<Barcode>): List<ScanAction> = mlKitActions(barcodes)
