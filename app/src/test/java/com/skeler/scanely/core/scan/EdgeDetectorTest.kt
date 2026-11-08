package com.skeler.scanely.core.scan

import com.skeler.scanely.core.geometry.Quad
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

class EdgeDetectorTest {

    /** A luma frame: [layers] painted in order (each a convex quad and a level) over [ground], plus noise. */
    private fun frame(
        w: Int,
        h: Int,
        ground: Int,
        layers: List<Pair<Quad, Int>>,
        noise: Int = 3,
        stride: Int = w,
        seed: Int = 7
    ): LumaFrame {
        val rnd = Random(seed)
        val data = ByteArray(stride * h) { 0x55 } // padding past width is garbage, as in a camera Y plane
        for (y in 0 until h) for (x in 0 until w) {
            var v = 0f
            for (sy in 0 until 4) for (sx in 0 until 4) {
                val px = x + (sx + 0.5f) / 4
                val py = y + (sy + 0.5f) / 4
                var level = ground
                for ((q, l) in layers) if (inside(q, px, py)) level = l
                v += level
            }
            val n = if (noise == 0) 0 else rnd.nextInt(-noise, noise + 1)
            data[y * stride + x] = (v / 16 + n).toInt().coerceIn(0, 255).toByte()
        }
        return LumaFrame(data, w, h, stride)
    }

    private fun inside(q: Quad, x: Float, y: Float): Boolean = (0 until 4).all { i ->
        val j = (i + 1) % 4
        (q.x(j) - q.x(i)) * (y - q.y(i)) - (q.y(j) - q.y(i)) * (x - q.x(i)) >= 0
    }

    private fun maxCornerError(a: Quad, b: Quad) = (0 until 4).maxOf { hypot(a.x(it) - b.x(it), a.y(it) - b.y(it)) }

    private val page = Quad(floatArrayOf(110f, 70f, 380f, 95f, 400f, 560f, 80f, 530f))

    @Test
    fun findsAPageOnADarkDesk() {
        val d = EdgeDetector.detect(frame(480, 640, 70, listOf(page to 225)))
        assertNotNull(d)
        assertTrue("error ${maxCornerError(d!!.quad, page)}", maxCornerError(d.quad, page) < 1.5f)
        assertTrue("confidence ${d.confidence}", d.confidence > 0.6f)
    }

    @Test
    fun findsAWhitePageOnALightDesk() {
        val d = EdgeDetector.detect(frame(480, 640, 212, listOf(page to 236)))
        assertNotNull(d)
        assertTrue("error ${maxCornerError(d!!.quad, page)}", maxCornerError(d.quad, page) < 1.5f)
    }

    @Test
    fun picksTheLitScreenNotTheLaptopLid() {
        val lid = Quad(floatArrayOf(60f, 50f, 430f, 60f, 425f, 330f, 65f, 320f))
        val screen = Quad(floatArrayOf(80f, 70f, 410f, 79f, 405f, 310f, 85f, 301f))
        val d = EdgeDetector.detect(frame(480, 360, 120, listOf(lid to 22, screen to 180)))
        assertNotNull(d)
        assertTrue("error ${maxCornerError(d!!.quad, screen)}", maxCornerError(d.quad, screen) < 1.5f)
    }

    @Test
    fun findsASmallCard() {
        val card = Quad(floatArrayOf(170f, 140f, 320f, 150f, 312f, 245f, 165f, 236f))
        val d = EdgeDetector.detect(frame(480, 360, 95, listOf(card to 205)))
        assertNotNull(d)
        assertTrue("error ${maxCornerError(d!!.quad, card)}", maxCornerError(d.quad, card) < 1.5f)
    }

    @Test
    fun cameraRowStrideIsHonoured() {
        val d = EdgeDetector.detect(frame(480, 640, 70, listOf(page to 225), stride = 512))
        assertNotNull(d)
        assertTrue(maxCornerError(d!!.quad, page) < 1.5f)
    }

    @Test
    fun nothingOnTheDeskFindsNothing() {
        assertNull(EdgeDetector.detect(frame(480, 640, 120, emptyList(), noise = 6)))
    }

    @Test
    fun previewFramesAreFast() {
        for ((w, h) in listOf(320 to 240, 480 to 360)) {
            val f = frame(w, h, 70, listOf(Quad(FloatArray(8) { i -> page.pts[i] * w / 480f }) to 225))
            repeat(30) { EdgeDetector.detect(f) } // warm-up
            val runs = 50
            val start = System.nanoTime()
            repeat(runs) { EdgeDetector.detect(f) }
            val ms = (System.nanoTime() - start) / 1e6 / runs
            println("EdgeDetector ${w}x$h on JVM: ${"%.2f".format(ms)} ms/frame")
            assertTrue("$ms ms/frame", ms < 30)
        }
    }

    /** A fixture as the camera's analysis stream sees it: luma at about [longSide] px on the long side. */
    private fun cameraFrame(fx: ScanFixture, longSide: Int): Pair<LumaFrame, Float> {
        val photo = fx.photo()
        val full = LumaFrame.fromArgb(photo.pixels, photo.width, photo.height)
        val factor = (maxOf(photo.width, photo.height) + longSide - 1) / longSide
        val small = full.downscaled(factor)
        val bytes = ByteArray(small.px.size) { small.px[it].toInt().coerceIn(0, 255).toByte() }
        return LumaFrame(bytes, small.width, small.height) to factor.toFloat()
    }

    @Test
    fun livePreviewFramesAreDetectedOnASmallWorkImage() {
        // 640 x 480 is what ImageAnalysis delivers (ScanCameraBinding). The live outline only drives the
        // overlay and auto-capture; the crop screen detects again on the full still.
        for (fx in ScanFixtures.load("synthetic")) {
            val (frame, factor) = cameraFrame(fx, 640)
            repeat(5) { EdgeDetector.detect(frame); EdgeDetector.detectLive(frame) } // warm-up
            val runs = 10
            var t = System.nanoTime()
            repeat(runs) { EdgeDetector.detect(frame) }
            val stillMs = (System.nanoTime() - t) / 1e6 / runs
            t = System.nanoTime()
            var d: QuadDetection? = null
            repeat(runs) { d = EdgeDetector.detectLive(frame) }
            val liveMs = (System.nanoTime() - t) / 1e6 / runs
            println("${fx.name} ${frame.width}x${frame.height}: live ${"%.2f".format(liveMs)} ms, full ${"%.2f".format(stillMs)} ms")
            assertNotNull("${fx.name}: nothing found live", d)
            val found = d!!.quad.scaled(factor, factor)
            val err = ScanMetrics.cornerError(found, fx.corners)
            assertTrue("${fx.name}: live mean corner error ${err.meanPercent}%", err.meanPercent < 1.5f)
            assertTrue("${fx.name}: live $liveMs ms is not well under full $stillMs ms", liveMs < stillMs * 0.5)
        }
    }

    @Test
    fun syntheticPhotosAreDetectedWithinOnePercentOfTheDiagonal() {
        for (fx in ScanFixtures.load("synthetic")) {
            val photo = fx.photo()
            val frame = LumaFrame.fromArgb(photo.pixels, photo.width, photo.height)
            EdgeDetector.detect(frame) // warm-up
            val start = System.nanoTime()
            val d = EdgeDetector.detect(frame)
            println("${fx.name} ${photo.width}x${photo.height} still: ${(System.nanoTime() - start) / 1_000_000} ms")
            assertNotNull("${fx.name}: nothing found", d)
            val err = ScanMetrics.cornerError(d!!.quad, fx.corners)
            println("${fx.name}: mean ${"%.2f".format(err.meanPercent)}%, max ${"%.2f".format(err.maxPercent)}%")
            println("  found ${d.quad.pts.joinToString { "%.1f".format(it) }}\n  truth ${fx.corners.pts.joinToString { "%.1f".format(it) }}")
            assertTrue("${fx.name}: mean corner error ${err.meanPercent}% of diagonal", err.meanPercent < 1f)
        }
    }
}
