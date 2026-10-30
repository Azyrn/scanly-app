package com.skeler.scanely.core.ocr.paddle

import android.graphics.Bitmap
import javax.inject.Inject
import javax.inject.Singleton

private const val DET_LIMIT = 960
private const val MIN_CONFIDENCE = 0.3f

/**
 * Reads the text lines of an upright page image with the bundled PP-OCR models and says where each
 * one is. The same detection and recognition as [PaddleOcrService], without its settings, rotation
 * and Markdown steps: the caller wants boxes in its own coordinates, so the image is read as given.
 * The script is chosen per page from the bundled packs (Arabic, or the Latin-capable universal one).
 *
 * Blocking and heavy: call it off the main thread.
 */
@Singleton
class PaddleLineReader @Inject constructor(private val engine: PaddleOcrEngine) {

    fun read(page: Bitmap): List<ReadLine> {
        val quads = engine.detect(page, DET_LIMIT)
        if (quads.isEmpty()) return emptyList()
        val pixels = ImageOps.Page(page)
        val crops = quads.map { ImageOps.cropQuad(pixels, it) }
        try {
            val pack = ScriptPack.DEFAULT
            val recognised = preferBundledScript(engine, crops, pack, engine.recognizeWithLatin(crops, pack))
            return quads.indices.mapNotNull { i ->
                val line = recognised[i]
                if (line.text.isBlank() || line.confidence < MIN_CONFIDENCE) {
                    null
                } else {
                    val q = quads[i]
                    ReadLine(line.text, line.confidence, q.minX, q.minY, q.maxX, q.maxY)
                }
            }
        } finally {
            crops.forEach { it.recycle() }
        }
    }
}
