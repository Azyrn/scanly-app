package com.skeler.scanely.core.pdftools.imagetools

import com.skeler.scanely.R
import com.skeler.scanely.core.pdftools.ToolJobInput
import com.skeler.scanely.core.pdftools.ToolResult
import javax.inject.Inject
import javax.inject.Singleton

private const val SUFFIX_COMPRESSED = "_compressed"

/**
 * Makes each image smaller while keeping its format, so a compressed PNG still has its
 * transparency and a JPEG still opens everywhere a JPEG did. PNG is lossless, so for PNGs the only
 * gain is dropped metadata unless the user lets them become JPEGs.
 *
 * A re-encode can come out larger (an already well-compressed JPEG, a tiny PNG); then the original
 * bytes are handed back instead, counted in a note, rather than a bigger "compressed" file.
 */
@Singleton
class CompressImageJob @Inject constructor(private val batch: ImageBatch) {

    suspend fun compress(input: ToolJobInput, quality: Int, pngToJpg: Boolean): ToolResult =
        batch.run(
            input,
            label = "compress image",
            showSizeChange = true,
            keptOriginalNote = R.plurals.pdf_tool_img_already_small
        ) { file ->
            val format = sniff(file)
            val target = targetFormat(format, pngToJpg)
            val output = if (target == null) {
                null
            } else {
                var bitmap = decode(file).bitmap
                if (target == EncodeFormat.JPEG) bitmap = flattenOnWhite(bitmap)
                write(bitmap, file, input.run, SUFFIX_COMPRESSED, target, quality)
            }
            if (output != null && output.sizeBytes < file.sizeBytes) {
                ImageOutcome(output)
            } else {
                output?.file?.delete()
                ImageOutcome(copyOriginal(file, input.run, SUFFIX_COMPRESSED, format), keptOriginal = true)
            }
        }

    /**
     * Null means "keep the original": a GIF re-encoded here would lose its animation, so there is
     * nothing safe to gain. Anything unrecognised is tried as a JPEG.
     */
    private fun targetFormat(format: ImageFormat, pngToJpg: Boolean): EncodeFormat? = when (format) {
        ImageFormat.JPEG, ImageFormat.HEIF, ImageFormat.OTHER -> EncodeFormat.JPEG
        ImageFormat.PNG, ImageFormat.BMP -> if (pngToJpg) EncodeFormat.JPEG else EncodeFormat.PNG
        ImageFormat.WEBP -> EncodeFormat.WEBP_LOSSY
        ImageFormat.GIF -> null
    }

    companion object {
        const val QUALITY_LIGHT = 90
        const val QUALITY_RECOMMENDED = 75
        const val QUALITY_STRONG = 50
    }
}
