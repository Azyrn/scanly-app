package com.skeler.scanely.core.scan

import com.skeler.scanely.core.geometry.Geometry
import com.skeler.scanely.core.geometry.Quad
import com.skeler.scanely.core.idcard.IdCardLayout
import com.skeler.scanely.core.idcard.IdDocumentKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The scanner's geometry on fixed sample photos (app/src/test/resources/scan_sample: a page on a desk at an
 * angle, and the front and back of a card, a quarter the size of tests/fixtures/scan/synthetic, with their
 * exact corners): corner ordering, the perspective transform, and the ID-card page layout.
 */
class ScanSampleImagesTest {

    private val dir = File(requireNotNull(javaClass.classLoader?.getResource("scan_sample")).toURI())
    private val corners = JSONObject(File(dir, "corners.json").readText())

    private fun photo(name: String) = ScanFixtures.read(File(dir, "$name.jpg"))
    private fun corners(name: String) = ScanFixtures.quad(corners.getJSONObject(name).getJSONArray("corners"))

    private fun corner(q: Quad, i: Int) = q.x(i) to q.y(i)
    private fun maxCornerError(a: Quad, b: Quad) = (0 until 4).maxOf { hypot(a.x(it) - b.x(it), a.y(it) - b.y(it)) }

    private fun shuffled(q: Quad, order: List<Int>) =
        Quad(FloatArray(8) { k -> if (k % 2 == 0) q.x(order[k / 2]) else q.y(order[k / 2]) })

    private fun permutations(items: List<Int>): List<List<Int>> =
        if (items.size <= 1) listOf(items) else items.flatMap { i -> permutations(items - i).map { listOf(i) + it } }

    @Test
    fun `corners in any order come back top-left, top-right, bottom-right, bottom-left`() {
        for (name in listOf("angled_page", "id_front", "id_back")) {
            val truth = corners(name)
            for (order in permutations(listOf(0, 1, 2, 3))) {
                assertEquals("$name $order", truth, Geometry.orderQuad(shuffled(truth, order)))
            }
            // QuadShape.normalized takes corners in either winding, starting anywhere
            for (start in 0 until 4) {
                val cw = List(4) { (start + it) % 4 }
                assertEquals("$name cw from $start", truth, QuadShape.normalized(shuffled(truth, cw).pts))
                assertEquals("$name ccw from $start", truth, QuadShape.normalized(shuffled(truth, cw.reversed()).pts))
            }
        }
    }

    @Test
    fun `edge detection on the sample photos finds the corners in order`() {
        for (name in listOf("angled_page", "id_front", "id_back")) {
            val p = photo(name)
            val found = EdgeDetector.detect(LumaFrame.fromArgb(p.pixels, p.width, p.height))
            assertNotNull(name, found)
            val error = maxCornerError(found!!.quad, corners(name))
            // 1 % of the long side: the corner a user would otherwise drag by hand
            assertTrue("$name corner error $error px", error < maxOf(p.width, p.height) * 0.01f)
            val (tlx, tly) = corner(found.quad, 0)
            val (brx, bry) = corner(found.quad, 2)
            assertTrue("$name top-left is above and left of bottom-right", tlx < brx && tly < bry)
        }
    }

    @Test
    fun `perspective transform flattens the angled page onto the true page`() {
        val p = photo("angled_page")
        val truth = ScanFixtures.read(File(dir, "angled_page_truth.png"))
        val right = corners("angled_page")
        // The photo is lit unevenly and darker than the truth, so compare by normalized correlation, which
        // ignores brightness and contrast. A quad off by only 2 px per corner already drops it to about 0.2.
        val score = correlation(PageWarp.warp(p.pixels, p.width, p.height, right), truth)
        assertTrue("correlation $score", score > 0.6f)
        val off = Quad(FloatArray(8) { right.pts[it] + 2f })
        val offScore = correlation(PageWarp.warp(p.pixels, p.width, p.height, off), truth)
        assertTrue("off by 2 px scores lower: $offScore vs $score", offScore < score - 0.3f)
    }

    /**
     * Pearson correlation of luma between [flat] and [truth], [flat] sampled on the truth's grid. The sample was
     * made with a hand-picked homography, not a camera, so its aspect can't be recovered from the photo (the
     * pinhole-camera page in PageWarpTest checks that); stretching to the truth's size compares content only.
     */
    private fun correlation(flat: FlatPage, truth: Argb): Float {
        val n = truth.width * truth.height
        val a = FloatArray(n)
        val b = FloatArray(n)
        for (y in 0 until truth.height) for (x in 0 until truth.width) {
            val fx = ((x + 0.5f) * flat.width / truth.width).toInt().coerceIn(0, flat.width - 1)
            val fy = ((y + 0.5f) * flat.height / truth.height).toInt().coerceIn(0, flat.height - 1)
            a[y * truth.width + x] = luma(flat.pixels[fy * flat.width + fx]).toFloat()
            b[y * truth.width + x] = luma(truth.pixels[y * truth.width + x]).toFloat()
        }
        val ma = a.average().toFloat()
        val mb = b.average().toFloat()
        var ab = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in 0 until n) {
            val da = a[i] - ma
            val db = b[i] - mb
            ab += da * db
            aa += da * da
            bb += db * db
        }
        return (ab / sqrt(aa * bb)).toFloat()
    }

    @Test
    fun `ID card front and back are warped to card shape and laid out at true size on one page`() {
        val sides = listOf("id_front", "id_back").map { name ->
            val p = photo(name)
            PageWarp.warp(p.pixels, p.width, p.height, corners(name), ScanMode.ID_CARD.forcedAspect(IdDocumentKind.ID_CARD))
        }
        for (side in sides) {
            val aspect = side.width.toFloat() / side.height
            assertEquals("card aspect", IdDocumentKind.ID_CARD.aspect, aspect, 0.01f)
        }

        val placed = IdCardLayout.layout(IdDocumentKind.ID_CARD, sides.map { it.width to it.height })
        assertEquals(2, placed.size)
        val dpi = IdCardLayout.DEFAULT_DPI
        val margin = IdCardLayout.mmToPx(IdCardLayout.MARGIN_MM, dpi)
        for (side in placed) {
            assertTrue("a warped card snaps to the card size", side.snapped)
            assertEquals(85.6f, side.image.width * 25.4f / dpi, 0.5f)
            assertEquals(53.98f, side.image.height * 25.4f / dpi, 0.5f)
            assertTrue(side.box.left >= margin && side.box.right <= IdCardLayout.pageWidthPx() - margin)
        }
        // front above back, one gap apart, both on the same A4 page
        assertEquals(IdCardLayout.mmToPx(IdCardLayout.GAP_MM), placed[1].box.top - placed[0].box.bottom)
        assertTrue(placed[1].box.bottom <= IdCardLayout.pageHeightPx() - margin)
    }

    private fun luma(p: Int) = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
}
