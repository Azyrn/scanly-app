package com.skeler.scanely.core.scan

import com.skeler.scanely.core.geometry.Quad
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuadStabilizerTest {

    private val frameW = 480
    private val frameH = 640

    private fun det(dx: Float = 0f, dy: Float = 0f, confidence: Float = 0.9f) = QuadDetection(
        Quad(floatArrayOf(60f + dx, 80f + dy, 420f + dx, 90f + dy, 430f + dx, 560f + dy, 50f + dx, 550f + dy)),
        confidence
    )

    /** Feeds one frame every 66 ms (15 fps); returns the times (ms) at which capture fired. */
    private fun run(s: QuadStabilizer, frames: List<QuadDetection?>): List<Long> {
        val fired = ArrayList<Long>()
        frames.forEachIndexed { i, d ->
            val t = i * 66L
            if (s.offer(d, frameW, frameH, t)) fired += t
        }
        return fired
    }

    @Test
    fun steadyQuadFiresAfterTheWindowNotBefore() {
        val s = QuadStabilizer()
        val fired = run(s, List(20) { det(dx = (it % 2) * 1.5f) })   // 1.5 px jitter
        assertTrue("fired at $fired", fired.isNotEmpty())
        assertTrue("first fire ${fired.first()} ms", fired.first() >= QuadStabilizer.DEFAULT_WINDOW_MS)
        assertTrue("first fire ${fired.first()} ms", fired.first() < QuadStabilizer.DEFAULT_WINDOW_MS + 140)
    }

    @Test
    fun movingQuadNeverFires() {
        val s = QuadStabilizer()
        // drifting 6 px per frame: a phone still being aimed
        assertTrue(run(s, List(30) { det(dx = it * 6f) }).isEmpty())
    }

    @Test
    fun aJumpRestartsTheWindow() {
        val s = QuadStabilizer()
        val frames = List(8) { det() } + List(20) { det(dx = 40f) }
        val fired = run(s, frames)
        // the jump at frame 8 (528 ms) restarts the clock
        assertTrue("fired at $fired", fired.isNotEmpty() && fired.first() >= 528 + QuadStabilizer.DEFAULT_WINDOW_MS)
    }

    @Test
    fun lostOrDoubtfulDetectionResets() {
        val s = QuadStabilizer()
        val frames = List(9) { det() } + listOf(null) + List(3) { det() } +
            listOf(det(confidence = 0.1f)) + List(4) { det() }
        assertTrue(run(s, frames).isEmpty())
    }

    @Test
    fun resetForgetsHistory() {
        val s = QuadStabilizer()
        for (i in 0 until 9) s.offer(det(), frameW, frameH, i * 66L)
        s.reset()
        assertFalse(s.offer(det(), frameW, frameH, 9 * 66L))
    }
}
