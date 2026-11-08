package com.skeler.scanely.core.image

import com.skeler.scanely.core.geometry.Quad
import com.skeler.scanely.core.scan.Argb
import com.skeler.scanely.core.scan.PageWarp
import com.skeler.scanely.core.scan.ScanFixture
import com.skeler.scanely.core.scan.ScanFixtures
import com.skeler.scanely.core.scan.ScanMetrics
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

/**
 * The filters on the synthetic scan photos, scored by [ScanMetrics.inkLostPercent]: content that a
 * filter turns white is gone for the user.
 *
 * The un-flattened case is the reported bug: GMS returned a laptop-screen photo uncropped (bezel,
 * keyboard, desk) and dark, and the Whiteboard filter blew the screen's text away.
 */
class FilterOnPhotosTest {

    private fun fixture(name: String): ScanFixture = ScanFixtures.load("synthetic").first { it.name == name }

    /** The page as the app holds it: [quad] of the photo, flattened at most 2200 px long. */
    private fun page(fx: ScanFixture, quad: Quad?): Pair<Argb, Quad> {
        val p = fx.photo()
        val w = p.width.toFloat()
        val h = p.height.toFloat()
        val q = quad ?: Quad(floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h))
        val flat = PageWarp.warp(p.pixels, p.width, p.height, q)
        return Argb(flat.pixels, flat.width, flat.height) to q
    }

    private fun inkLost(fx: ScanFixture, quad: Quad?, filter: (IntArray, Int, Int) -> IntArray): Float {
        val (img, q) = page(fx, quad)
        val out = Argb(filter(img.pixels, img.width, img.height), img.width, img.height)
        return ScanMetrics.inkLostPercent(fx, out, q)!!
    }

    @Test
    fun whiteboardFilterKeepsTheTextOfAnUnflattenedScreenPhoto() {
        val fx = fixture("screen")
        val lost = inkLost(fx, null, WhiteboardEnhancer::enhance)
        println("WHITEBOARD on un-flattened screen.jpg: ink lost $lost%")
        assertTrue("ink lost $lost%", lost < 2f)
    }

    @Test
    fun whiteboardFilterKeepsTheTextOfAFlattenedScreen() {
        val fx = fixture("screen")
        val lost = inkLost(fx, fx.corners, WhiteboardEnhancer::enhance)
        println("WHITEBOARD on flattened screen: ink lost $lost%")
        assertTrue("ink lost $lost%", lost < 2f)
    }

    @Test
    fun whiteboardFilterKeepsMarkersOfAnUnflattenedBoardPhoto() {
        val fx = fixture("whiteboard")
        val lost = inkLost(fx, null, WhiteboardEnhancer::enhance)
        println("WHITEBOARD on un-flattened whiteboard.jpg: ink lost $lost%")
        assertTrue("ink lost $lost%", lost < 2f)
    }

    @Test
    fun whiteboardFilterKeepsMarkersOfAFlattenedBoard() {
        val fx = fixture("whiteboard")
        val lost = inkLost(fx, fx.corners, WhiteboardEnhancer::enhance)
        println("WHITEBOARD on flattened whiteboard: ink lost $lost%")
        assertTrue("ink lost $lost%", lost < 2f)
    }

    /** True when photo point (x, y) is inside convex quad [q]. */
    private fun inside(q: Quad, x: Float, y: Float) = (0 until 4).all { i ->
        val j = (i + 1) % 4
        (q.x(j) - q.x(i)) * (y - q.y(i)) - (q.y(j) - q.y(i)) * (x - q.x(i)) >= 0
    }

    /**
     * Hypothesis 1 of the report: the board estimate is the brightest value of each block, so in a
     * dark region (desk, keyboard, bezel) it is dark too, and dividing by it turns the region white.
     * Measured as the share of dark photo pixels (luma < 60) outside the document that come out
     * near-white (>= 200).
     */
    @Test
    fun whiteboardFilterKeepsTheDarkSurroundingsOfAnUnflattenedScreenPhotoDark() {
        val fx = fixture("screen")
        val (img, q) = page(fx, null)
        val out = WhiteboardEnhancer.enhance(img.pixels, img.width, img.height)
        val sx = (q.x(1) - q.x(0)) / img.width
        val sy = (q.y(3) - q.y(0)) / img.height
        var dark = 0
        var blown = 0
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val i = y * img.width + x
            if (inside(fx.corners, (x + 0.5f) * sx, (y + 0.5f) * sy) || ScanMetrics.luma(img.pixels[i]) >= 60) continue
            dark++
            if (ScanMetrics.luma(out[i]) >= 200) blown++
        }
        val share = 100f * blown / dark
        println("WHITEBOARD on un-flattened screen.jpg: $share% of the dark surroundings blown to white")
        assertTrue("$share% of dark surroundings turned white", share < 5f)
    }

    /**
     * AUTO on the flattened dark screen: the backlit page (162 levels in the photo) must come out
     * white enough to read as paper, with the grey text kept and clearly darker than the page.
     */
    @Test
    fun autoFilterMakesADarkScreenReadable() {
        val fx = fixture("screen")
        val (img, _) = page(fx, fx.corners)
        val out = Argb(DocumentEnhancer.enhance(img.pixels, img.width, img.height), img.width, img.height)
        val truth = fx.truth()!!
        val ink = fx.ink()!!
        val ground = ArrayList<Int>()
        val text = ArrayList<Int>()
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val u = floor((x + 0.5f) * truth.width / img.width).toInt()
            val v = floor((y + 0.5f) * truth.height / img.height).toInt()
            val t = truth.pixels[v * truth.width + u]
            val l = ScanMetrics.luma(out.pixels[y * img.width + x])
            if (ink[v * truth.width + u]) text += l else if (ScanMetrics.luma(t) == 255) ground += l
        }
        ground.sort()
        text.sort()
        val groundMedian = ground[ground.size / 2]
        val textMedian = text[text.size / 2]
        val lost = ScanMetrics.inkLostPercent(fx, out, fx.corners)!!
        println("AUTO on flattened screen: page $groundMedian, text $textMedian, ink lost $lost%")
        assertTrue("page median $groundMedian", groundMedian >= 225)
        assertTrue("text median $textMedian vs page $groundMedian", groundMedian - textMedian >= 90)
        assertTrue("ink lost $lost%", lost < 2f)
    }
}
