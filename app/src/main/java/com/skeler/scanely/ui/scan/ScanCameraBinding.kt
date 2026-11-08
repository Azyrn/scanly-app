package com.skeler.scanely.ui.scan

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "ScanCamera"

/** The analysis size CameraX recommends for live work (its default bound). */
private val ANALYSIS_SIZE = Size(640, 480)

/**
 * Binds preview, full-quality capture and live analysis to the back camera, all at 4:3 like the sensor, so
 * the preview, the analysed frame and the photo show the same field of view.
 */
@Composable
fun BindScanCamera(
    previewView: PreviewView,
    analyzer: ImageAnalysis.Analyzer,
    onBound: (Camera, ImageCapture) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnBound = rememberUpdatedState(onBound)
    val fourByThree = remember {
        ResolutionSelector.Builder().setAspectRatioStrategy(
            AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        ).build()
    }
    val preview = remember { Preview.Builder().setResolutionSelector(fourByThree).build() }
    val capture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(fourByThree)
            .build()
    }
    val analysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(ANALYSIS_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    )
                    .build()
            )
            .build()
    }
    val boundProvider = remember { arrayOfNulls<ProcessCameraProvider>(1) }

    DisposableEffect(analyzer) {
        val executor = Executors.newSingleThreadExecutor()
        analysis.setAnalyzer(executor, analyzer)
        onDispose {
            analysis.clearAnalyzer()
            executor.shutdown()
        }
    }

    // A Preview still holding the PreviewView's surface provider would pin the destroyed activity.
    DisposableEffect(preview) {
        onDispose {
            preview.surfaceProvider = null
            boundProvider[0]?.unbind(preview, capture, analysis)
        }
    }

    LaunchedEffect(Unit) {
        try {
            val provider = context.cameraProvider()
            boundProvider[0] = provider
            preview.surfaceProvider = previewView.surfaceProvider
            provider.unbindAll()
            val camera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                capture,
                analysis
            )
            currentOnBound.value(camera, capture)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Camera binding failed", e)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Camera binding failed", e)
        }
    }
}

/** Takes a full-quality photo into [file]. */
fun takePhoto(
    context: Context,
    capture: ImageCapture,
    file: File,
    onSaved: (File) -> Unit,
    onError: (ImageCaptureException) -> Unit
) {
    capture.takePicture(
        ImageCapture.OutputFileOptions.Builder(file).build(),
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) = onSaved(file)
            override fun onError(exception: ImageCaptureException) = onError(exception)
        }
    )
}

private suspend fun Context.cameraProvider(): ProcessCameraProvider = suspendCancellableCoroutine { continuation ->
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener(
        {
            runCatching { future.get() }
                .onSuccess { continuation.resume(it) }
                .onFailure { continuation.resumeWithException(it) }
        },
        ContextCompat.getMainExecutor(this)
    )
}
