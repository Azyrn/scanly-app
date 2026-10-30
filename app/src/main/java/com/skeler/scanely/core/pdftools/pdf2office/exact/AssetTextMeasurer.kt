package com.skeler.scanely.core.pdftools.pdf2office.exact

import android.content.res.AssetManager
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log

private const val TAG = "AssetTextMeasurer"
private const val MEASURE_SIZE = 1000f
private const val LATIN_FEATURES = "'kern' 0, 'liga' 0, 'clig' 0"

/**
 * Measures with the metric twins bundled in assets/fonts (see [OfficeFamily]): the same advance
 * widths Word gets from Times New Roman/Arial/Calibri and LibreOffice from Liberation/Carlito.
 * Kerning and ligatures are off because neither app applies them to body text by default.
 *
 * Not thread-safe: one instance per conversion.
 */
class AssetTextMeasurer(private val assets: AssetManager) : TextMeasurer {

    private val typefaces = HashMap<String, Typeface>()
    private val paint = Paint().apply {
        isAntiAlias = true
        isSubpixelText = true
    }

    override fun width(
        text: String,
        family: OfficeFamily,
        arabic: Boolean,
        bold: Boolean,
        italic: Boolean,
        sizePt: Float
    ): Float {
        val asset = when {
            arabic -> if (bold) OfficeFamily.ARABIC_BOLD else OfficeFamily.ARABIC_REGULAR
            else -> family.asset(bold, italic)
        }
        paint.typeface = typeface(asset)
        // Arabic joins through its own substitutions (init, medi, fina, ligatures), which the
        // receiving app applies, so they are measured; Latin runs are unkerned and unligated.
        paint.fontFeatureSettings = if (arabic) null else LATIN_FEATURES
        // Measured at a large size and scaled: at 10-12 px the font is hinted and every advance is
        // rounded to whole pixels, which is off by up to a third of a point per letter.
        paint.textSize = MEASURE_SIZE
        return paint.measureText(text) * sizePt / MEASURE_SIZE
    }

    private fun typeface(asset: String): Typeface = typefaces.getOrPut(asset) {
        runCatching { Typeface.createFromAsset(assets, asset) }
            .onFailure { Log.w(TAG, "No font at $asset", it) }
            .getOrDefault(Typeface.DEFAULT)
    }
}
