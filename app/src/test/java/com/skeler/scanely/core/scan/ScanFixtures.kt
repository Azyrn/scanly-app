package com.skeler.scanely.core.scan

import com.skeler.scanely.core.geometry.Quad
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.ImageReader

/** ARGB pixels of a decoded image. */
class Argb(val pixels: IntArray, val width: Int, val height: Int)

/**
 * One photo of tests/fixtures/scan/<set>/ with its hand-marked (real) or exact (synthetic) corners.
 *
 * Photos are decoded at most [ScanFixtures.MAX_LONG_SIDE] px long, as the app decodes them, by
 * keeping every [subsample]-th pixel; [corners] are in those pixels. [photo] decodes on every call,
 * so a loop over fixtures holds one photo at a time.
 */
class ScanFixture(
    val set: String,
    val name: String,
    photoCorners: Quad,
    val mode: ScanMode,
    val whiteGround: Boolean,
    private val dir: File
) {
    private val file = File(dir, "$name.jpg")
    private val subsample = ScanFixtures.subsampleFor(file)

    /** Document corners in [photo] pixels. */
    val corners: Quad = ScanFixtures.toSubsampled(photoCorners, subsample)

    fun photo(): Argb = ScanFixtures.read(file, subsample)

    /** The flat document as it should come out; synthetic fixtures only. */
    fun truth(): Argb? = File(dir, "truth/$name.png").takeIf { it.exists() }?.let(ScanFixtures::read)

    /** true where the truth has content; synthetic fixtures only. */
    fun ink(): BooleanArray? = File(dir, "truth/${name}_ink.png").takeIf { it.exists() }?.let { f ->
        val m = ScanFixtures.read(f)
        BooleanArray(m.pixels.size) { (m.pixels[it] and 0xFF) > 127 }
    }

    /** GMS's output for this photo and the quad it used, when the lead has produced one. */
    fun gms(): GmsBaseline? {
        val base = File(ScanFixtures.root, "gms_baseline/$set")
        val json = File(base, "$name.json")
        val jpg = File(base, "$name.jpg")
        if (!json.exists() || !jpg.exists()) return null
        val quad = ScanFixtures.quad(JSONObject(json.readText()).getJSONArray("quad"))
        return GmsBaseline(ScanFixtures.toSubsampled(quad, subsample), ScanFixtures.read(jpg, ScanFixtures.subsampleFor(jpg)))
    }
}

class GmsBaseline(val quad: Quad, val output: Argb)

object ScanFixtures {

    /** The test JVM runs in app/; the fixtures live at the repo root. */
    val root: File = listOf(File("../tests/fixtures/scan"), File("tests/fixtures/scan")).first { it.isDirectory }

    fun load(set: String): List<ScanFixture> {
        val dir = File(root, set)
        val meta = File(dir, "corners.json").takeIf { it.exists() } ?: return emptyList()
        val json = JSONObject(meta.readText())
        return json.keys().asSequence().sorted().filter { File(dir, "$it.jpg").exists() }.map { name ->
            val rec = json.getJSONObject(name)
            ScanFixture(
                set = set,
                name = name,
                photoCorners = quad(rec.getJSONArray("corners")),
                mode = when (rec.getString("mode")) {
                    "id_card" -> ScanMode.ID_CARD
                    "whiteboard" -> ScanMode.WHITEBOARD
                    else -> ScanMode.DOCUMENT
                },
                whiteGround = rec.getBoolean("white_ground"),
                dir = dir
            )
        }.toList()
    }

    fun quad(a: JSONArray): Quad = Quad(FloatArray(8) { a.getJSONArray(it / 2).getDouble(it % 2).toFloat() })

    /** The app decodes every photo at most this long (DocumentScanViewModel's MAX_DIMENSION). */
    const val MAX_LONG_SIDE = 2200

    private fun <T> withReader(f: File, block: (ImageReader) -> T): T {
        val input = ImageIO.createImageInputStream(f) ?: error("cannot open $f")
        input.use {
            val reader = ImageIO.getImageReaders(it).next()
            try {
                reader.input = it
                return block(reader)
            } finally {
                reader.dispose()
            }
        }
    }

    /** Smallest whole-pixel step that brings [f] to at most [MAX_LONG_SIDE] px. */
    fun subsampleFor(f: File): Int = withReader(f) { r ->
        val long = maxOf(r.getWidth(0), r.getHeight(0))
        (long + MAX_LONG_SIDE - 1) / MAX_LONG_SIDE
    }

    /**
     * Full-photo coordinates -> coordinates in a photo decoded keeping every [step]-th pixel: kept
     * pixel i is source pixel i * step, whose centre i * step + 0.5 must land on i + 0.5.
     */
    fun toSubsampled(q: Quad, step: Int): Quad =
        Quad(FloatArray(8) { (q.pts[it] - 0.5f) / step + 0.5f })

    fun read(f: File, step: Int = 1): Argb = withReader(f) { r ->
        val param = r.defaultReadParam.apply { setSourceSubsampling(step, step, 0, 0) }
        val img = r.read(0, param)
        Argb(img.getRGB(0, 0, img.width, img.height, null, 0, img.width), img.width, img.height)
    }
}
