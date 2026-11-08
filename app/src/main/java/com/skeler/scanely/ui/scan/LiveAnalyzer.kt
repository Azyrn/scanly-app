package com.skeler.scanely.ui.scan

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.skeler.scanely.core.scan.EdgeDetector
import com.skeler.scanely.core.scan.LumaFrame
import com.skeler.scanely.core.scan.QuadStabilizer
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** What the camera sees now: the page outline on the upright frame, and whether it is holding still. */
data class LiveView(
    val quad: NormQuad? = null,
    /** Upright frame width / height, to place [quad] on the preview. */
    val frameAspect: Float = 0f,
    val steady: Boolean = false
)

/**
 * Runs page detection on every frame CameraX hands over (KEEP_ONLY_LATEST, so a slow frame drops the next
 * instead of queueing). Lives on the analysis executor's single thread; [onView] is called from there.
 */
class LiveAnalyzer(private val fpsLog: FpsLog?, private val onView: (LiveView) -> Unit) : ImageAnalysis.Analyzer {

    private val stabilizer = QuadStabilizer()
    private val restart = AtomicBoolean(false)
    private var luma = ByteArray(0)

    /** How long the outline must hold still before the stabilizer reports it steady. */
    val windowMillis: Long = QuadStabilizer.DEFAULT_WINDOW_MS

    /** Starts the steadiness count again from the next frame (after a photo); safe from any thread. */
    fun restartSteadiness() = restart.set(true)

    override fun analyze(image: ImageProxy) {
        try {
            if (restart.getAndSet(false)) stabilizer.reset()
            val started = System.nanoTime()
            val frame = lumaOf(image)
            // The ~640 x 480 plane is box-averaged to the detector's live work size.
            val found = EdgeDetector.detectLive(frame)
            val steady = stabilizer.offer(found, frame.width, frame.height, image.imageInfo.timestamp / NANOS_PER_MILLI)
            val turns = image.imageInfo.rotationDegrees / QUARTER_DEGREES
            val upright = found?.toNorm(frame.width, frame.height)?.turned(turns)
            val aspect = frame.width.toFloat() / frame.height
            val uprightAspect = if (turns % 2 == 0) aspect else 1f / aspect
            onView(LiveView(upright, uprightAspect, steady))
            fpsLog?.frame(System.nanoTime(), System.nanoTime() - started)
        } finally {
            image.close()
        }
    }

    /** The Y plane as it is: rows rowStride bytes apart (luma pixels are always 1 byte apart). */
    private fun lumaOf(image: ImageProxy): LumaFrame {
        val plane = image.planes[0]
        val buffer = plane.buffer
        buffer.rewind()
        if (luma.size != buffer.remaining()) luma = ByteArray(buffer.remaining())
        buffer.get(luma)
        return LumaFrame(luma, image.width, image.height, plane.rowStride)
    }

    private companion object {
        const val QUARTER_DEGREES = 90
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * Frames analysed per second over the last two seconds, written once a second to [file] (debug builds; One UI
 * hides app logcat). Single-threaded: only the analyzer thread calls [frame].
 */
class FpsLog(private val file: File) {
    private val stamps = ArrayDeque<Long>()
    private var lastWrite = 0L
    private var workNanos = 0L
    private var frames = 0

    init {
        runCatching { file.writeText("") }
    }

    fun frame(nowNanos: Long, analyseNanos: Long) {
        stamps.addLast(nowNanos)
        while (stamps.isNotEmpty() && nowNanos - stamps.first() > WINDOW_NANOS) stamps.removeFirst()
        workNanos += analyseNanos
        frames++
        if (nowNanos - lastWrite < SECOND_NANOS) return
        lastWrite = nowNanos
        val fps = stamps.size * SECOND_NANOS.toFloat() / WINDOW_NANOS
        val msPerFrame = workNanos / frames.toFloat() / NANOS_PER_MILLI
        workNanos = 0L
        frames = 0
        try {
            val line = String.format(
                Locale.US,
                "t=%d fps2s=%.1f analyseMs=%.1f%n",
                System.currentTimeMillis(),
                fps,
                msPerFrame
            )
            file.appendText(line)
        } catch (e: IOException) {
            Log.w("ScanFps", "Couldn't write ${file.name}", e)
        }
    }

    private companion object {
        const val SECOND_NANOS = 1_000_000_000L
        const val WINDOW_NANOS = 2 * SECOND_NANOS
        const val NANOS_PER_MILLI = 1_000_000f
    }
}
