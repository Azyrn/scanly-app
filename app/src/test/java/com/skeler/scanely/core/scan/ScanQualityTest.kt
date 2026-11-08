package com.skeler.scanely.core.scan

import com.skeler.scanely.core.geometry.Quad
import com.skeler.scanely.core.idcard.IdDocumentKind
import com.skeler.scanely.core.image.ScanFilter
import com.skeler.scanely.core.image.WhiteboardEnhancer
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detect -> warp -> the mode's default filter on every fixture photo, scored by [ScanMetrics].
 *
 * Prints one row per fixture. Synthetic rows are PROVISIONAL (our own renderer drew them); the gate
 * is GMS: wherever tests/fixtures/scan/gms_baseline/<set>/<name>.{jpg,json} exists, our result must
 * beat GMS's on that photo. Without a baseline the row says so and nothing is asserted.
 */
class ScanQualityTest {

    class Scored(
        val corners: ScanMetrics.CornerError,
        val tone: ScanMetrics.Tone,
        val inkLost: Float?,
        val hue: ScanMetrics.Hue?,
        val separation: Int?
    )

    @Test
    fun oursAgainstTruthAndGms() {
        val failures = ArrayList<String>()
        for (set in listOf("synthetic", "real")) {
            val fixtures = ScanFixtures.load(set)
            if (fixtures.isEmpty()) {
                println("[$set] no fixtures yet: skipped")
                continue
            }
            println("\n[$set]${if (set == "synthetic") " PROVISIONAL" else ""}")
            println(HEADER)
            println("(* pixels of the photo as the app decodes it, at most ${ScanFixtures.MAX_LONG_SIDE} px long)")
            for (fx in fixtures) failures += scoreOne(fx)
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun scoreOne(fx: ScanFixture): List<String> {
        val ours = OurScan.run(fx)
        val o = score(fx, ours.output, ours.quad)
        println(row(fx.name, "ours ${fx.mode.defaultFilter}", o))
        if (fx.mode == ScanMode.ID_CARD) {
            // the ID default is a choice between two filters: show the other one too
            val orig = OurScan.run(fx, ScanFilter.ORIGINAL)
            println(row(fx.name, "  alt ORIGINAL", score(fx, orig.output, orig.quad)))
        }
        val gms = fx.gms()
        if (gms == null) {
            println("${fx.name.padEnd(12)} gms: no baseline in gms_baseline/${fx.set}/ - not gated")
            return emptyList()
        }
        val g = score(fx, gms.output, gms.quad)
        println(row(fx.name, "gms", g))
        return beatsGms(fx, o, g)
    }

    private fun beatsGms(fx: ScanFixture, o: Scored, g: Scored): List<String> {
        val f = ArrayList<String>()
        val id = "${fx.set}/${fx.name}"
        if (o.corners.meanPx >= g.corners.meanPx) f += "$id: corner error ${o.corners.meanPx} >= GMS ${g.corners.meanPx}"
        val oi = o.inkLost
        val gi = g.inkLost
        if (oi != null && gi != null && oi > gi && oi > 0f) f += "$id: ink lost $oi% > GMS $gi%"
        if (!fx.whiteGround && o.tone.clippedWhitePercent > g.tone.clippedWhitePercent) {
            f += "$id: clipped white ${o.tone.clippedWhitePercent}% > GMS ${g.tone.clippedWhitePercent}%"
        }
        if (o.tone.rmsContrast < g.tone.rmsContrast) f += "$id: contrast ${o.tone.rmsContrast} < GMS ${g.tone.rmsContrast}"
        return f
    }

    companion object {
        const val HEADER = "fixture      pipeline           corner mean/max px*  %diag mean/max  rms   p5-p95   " +
            "clip%   inkLost%  hueKept%  sep"

        fun score(fx: ScanFixture, output: Argb, quad: Quad) = Scored(
            ScanMetrics.cornerError(quad, fx.corners),
            ScanMetrics.tone(output),
            ScanMetrics.inkLostPercent(fx, output, quad),
            if (fx.mode == ScanMode.WHITEBOARD) ScanMetrics.hue(fx, output, quad) else null,
            ScanMetrics.separation(fx, output, quad)
        )

        fun row(name: String, pipeline: String, s: Scored): String {
            val c = s.corners
            return name.padEnd(12) + " " + pipeline.padEnd(18) +
                " %7.1f / %-7.1f    %5.2f / %-5.2f   %5.1f  %3d-%-3d  %5.1f   %s   %s   %s".format(
                    c.meanPx, c.maxPx, c.meanPercent, c.maxPercent, s.tone.rmsContrast, s.tone.p5, s.tone.p95,
                    s.tone.clippedWhitePercent, s.inkLost?.let { "%7.2f".format(it) } ?: "      -",
                    s.hue?.let { "%6.1f".format(it.keptPercent) } ?: "     -",
                    s.separation?.let { "%4d".format(it) } ?: "   -"
                )
        }
    }
}

/** Our scan pipeline as the app runs it, on JVM pixels. */
object OurScan {

    class Result(val quad: Quad, val output: Argb)

    fun run(fx: ScanFixture, filter: ScanFilter = fx.mode.defaultFilter): Result {
        val photo = fx.photo()
        val found = EdgeDetector.detect(LumaFrame.fromArgb(photo.pixels, photo.width, photo.height))
        val w = photo.width.toFloat()
        val h = photo.height.toFloat()
        val quad = found?.quad ?: Quad(floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h))
        val page = PageWarp.warp(photo.pixels, photo.width, photo.height, quad, fx.mode.forcedAspect(IdDocumentKind.ID_CARD))
        val out = applyFilter(filter, fx.mode, page.pixels, page.width, page.height)
        return Result(quad, Argb(out, page.width, page.height))
    }

    fun applyFilter(filter: ScanFilter, mode: ScanMode, px: IntArray, w: Int, h: Int): IntArray = when (filter) {
        ScanFilter.ORIGINAL -> px
        ScanFilter.AUTO -> mode.autoEnhance(px, w, h)
        ScanFilter.WHITEBOARD -> WhiteboardEnhancer.enhance(px, w, h)
        else -> error("$filter is no mode's default; the harness does not run it")
    }
}
