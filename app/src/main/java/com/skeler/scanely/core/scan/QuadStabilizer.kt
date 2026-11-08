package com.skeler.scanely.core.scan

import com.skeler.scanely.core.geometry.Quad
import kotlin.math.hypot

/**
 * Decides when the live preview has held a document still long enough to capture it.
 *
 * Fed every analysed frame from one thread (the camera analyser): stable once every detection in the
 * last [windowMs] stayed within [maxDrift] (fraction of the frame diagonal) of the newest one, with
 * confidence of at least [minConfidence] throughout. A lost, doubtful or jumping detection restarts
 * the clock.
 */
class QuadStabilizer(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val maxDrift: Float = DEFAULT_MAX_DRIFT,
    private val minConfidence: Float = DEFAULT_MIN_CONFIDENCE
) {
    private class Seen(val quad: Quad, val timeMs: Long)

    private val history = ArrayDeque<Seen>()

    /** Adds one frame's result; true when the document has been steady for the whole window. */
    fun offer(detection: QuadDetection?, frameWidth: Int, frameHeight: Int, timeMs: Long): Boolean {
        if (detection == null || detection.confidence < minConfidence) {
            history.clear()
            return false
        }
        val limit = maxDrift * hypot(frameWidth.toFloat(), frameHeight.toFloat())
        // keep only the unbroken run of frames that agree with this one
        val keepFrom = history.indexOfLast { drift(it.quad, detection.quad) > limit } + 1
        repeat(keepFrom) { history.removeFirst() }
        history.addLast(Seen(detection.quad, timeMs))
        // the oldest frame still needed is the newest one at or before the window's start
        while (history.size > 1 && history[1].timeMs <= timeMs - windowMs) history.removeFirst()
        return timeMs - history.first().timeMs >= windowMs
    }

    fun reset() = history.clear()

    private fun drift(a: Quad, b: Quad) = (0 until 4).maxOf { hypot(a.x(it) - b.x(it), a.y(it) - b.y(it)) }

    companion object {
        const val DEFAULT_WINDOW_MS = 700L

        /** 1.5% of the diagonal: ~9 px on a 480x640 preview, hand shake but not re-aiming. */
        const val DEFAULT_MAX_DRIFT = 0.015f
        const val DEFAULT_MIN_CONFIDENCE = 0.5f
    }
}
