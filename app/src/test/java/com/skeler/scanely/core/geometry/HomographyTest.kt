package com.skeler.scanely.core.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class HomographyTest {

    private val quad = Quad(floatArrayOf(120f, 80f, 910f, 140f, 980f, 1300f, 60f, 1210f))

    @Test
    fun rectCornersLandOnTheQuad() {
        val h = Homography.rectToQuad(quad, 1000f, 1400f)
        val rect = listOf(0f to 0f, 1000f to 0f, 1000f to 1400f, 0f to 1400f)
        rect.forEachIndexed { i, (x, y) ->
            val (u, v) = h.map(x, y)
            assertEquals(quad.x(i), u, 0.05f)
            assertEquals(quad.y(i), v, 0.05f)
        }
    }

    @Test
    fun inverseUndoesTheMap() {
        val h = Homography.rectToQuad(quad, 1000f, 1400f)
        val back = h.inverse()
        for ((x, y) in listOf(10f to 20f, 500f to 700f, 990f to 1390f, 333f to 1111f)) {
            val (u, v) = h.map(x, y)
            val (x2, y2) = back.map(u, v)
            assertTrue("($x,$y) -> ($x2,$y2)", hypot(x2 - x, y2 - y) < 0.01f)
        }
    }

    @Test
    fun trueAspectOfATiltedPageIsRecovered() {
        val cam = PinholeCamera(3000, 4000)
        for ((w, h) in listOf(210.0 to 297.0, 85.6 to 53.98, 125.0 to 88.0)) {
            for (tilt in listOf(0.0, 20.0, 35.0)) {
                val q = cam.project(w, h, tiltDeg = tilt, yawDeg = 12.0, rollDeg = 7.0)
                val got = QuadAspect.estimate(q, cam.imageWidth, cam.imageHeight)
                val want = (w / h).toFloat()
                assertTrue("$w x $h tilt $tilt: aspect $got vs $want", abs(got / want - 1f) < 0.02f)
            }
        }
    }

    @Test
    fun edgeLengthsAloneWouldGetTheTiltedAspectWrong() {
        // proves the case above needs perspective: the naive mean-edge ratio is off by far more than 2%
        val cam = PinholeCamera(3000, 4000)
        val q = cam.project(210.0, 297.0, tiltDeg = 35.0)
        val top = hypot(q.x(1) - q.x(0), q.y(1) - q.y(0))
        val bottom = hypot(q.x(2) - q.x(3), q.y(2) - q.y(3))
        val left = hypot(q.x(3) - q.x(0), q.y(3) - q.y(0))
        val right = hypot(q.x(2) - q.x(1), q.y(2) - q.y(1))
        val naive = (top + bottom) / (left + right)
        assertTrue("naive $naive", abs(naive / (210f / 297f) - 1f) > 0.1f)
    }
}
