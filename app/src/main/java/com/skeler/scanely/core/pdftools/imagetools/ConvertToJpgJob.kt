package com.skeler.scanely.core.pdftools.imagetools

import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolResult
import javax.inject.Inject
import javax.inject.Singleton

/** High enough that the conversion itself isn't what the user notices; Compress is for size. */
private const val JPEG_QUALITY = 90

/**
 * Any image the phone can decode (PNG, WebP, HEIC, BMP, the first frame of a GIF) as a JPEG, the
 * format every upload form accepts. Transparent areas become white, as on paper — JPEG has no
 * alpha and would otherwise turn them black.
 */
@Singleton
class ConvertToJpgJob @Inject constructor(private val batch: ImageBatch) {

    suspend fun convert(input: ToolJobInput): ToolResult =
        batch.run(input, label = "convert to jpg") { file ->
            val bitmap = flattenOnWhite(decode(file).bitmap)
            ImageOutcome(write(bitmap, file, input.run, suffix = "", EncodeFormat.JPEG, JPEG_QUALITY))
        }
}
