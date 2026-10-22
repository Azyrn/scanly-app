package com.skeler.scanely.core.pdftools.imagetools

import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolResult
import javax.inject.Inject
import javax.inject.Singleton

/** Lossy WebP at this quality is visually the JPEG it came from, and still smaller. */
private const val WEBP_QUALITY = 90

enum class ImageTarget(val format: EncodeFormat) {
    PNG(EncodeFormat.PNG),

    /**
     * Lossy on purpose: a JPEG's pixels are already lossy, and lossless WebP of them is several
     * times the size of the JPEG for no visible gain.
     */
    WEBP(EncodeFormat.WEBP_LOSSY),

    /** 256 colours; fine for graphics and memes, visibly banded on photos. */
    GIF(EncodeFormat.GIF)
}

/** JPEGs (or any readable image) as PNG, WebP or a still GIF. */
@Singleton
class ConvertFromJpgJob @Inject constructor(private val batch: ImageBatch) {

    suspend fun convert(input: ToolJobInput, target: ImageTarget): ToolResult =
        batch.run(input, label = "convert from jpg") { file ->
            val bitmap = decode(file).bitmap
            ImageOutcome(write(bitmap, file, input.run, suffix = "", target.format, WEBP_QUALITY))
        }
}
