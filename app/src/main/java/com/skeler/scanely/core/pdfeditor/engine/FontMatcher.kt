package com.skeler.scanely.core.pdfeditor.engine

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import com.skeler.scanely.core.pdfeditor.model.OfficialFont
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * The style a run of original text appears to be set in.
 *
 * [sizePt] is fitted, not just reported: it is pdfium's size adjusted so the chosen font reproduces
 * the width the original run actually occupies on the page.
 */
data class DetectedFont(
    val font: OfficialFont,
    val sizePt: Float,
    /** How far the best candidate's width was from the original's, as a fraction. */
    val widthError: Float
) {
    /** Below this the match is close enough that showing it as a guess would be needless doubt. */
    val isConfident: Boolean get() = widthError <= CONFIDENT_WIDTH_ERROR

    private companion object {
        const val CONFIDENT_WIDTH_ERROR = 0.06f
    }
}

/**
 * Infers which bundled font a run of PDF text was set in, from its metrics.
 *
 * pdfium exposes a character's size but not its font name. Width is the signal that survives: the
 * bundled fonts are metric-compatible clones of the families official documents use (Liberation
 * Serif/Sans/Mono, Carlito, Caladea), so the clone reproducing the run's width is in practice the
 * clone of the font that set it. Courier cannot hide at 0.6 em per character.
 *
 * Width is then reused to fit the size, correcting text-matrix scaling that makes pdfium's reported
 * size disagree with the page.
 */
@Singleton
class FontMatcher @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    private val typefaces = mutableMapOf<OfficialFont, Typeface>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun detect(text: String, measuredWidthPt: Float, reportedSizePt: Float): DetectedFont =
        select(text, measuredWidthPt, reportedSizePt) { font, string, size ->
            synchronized(paint) {
                paint.typeface = typefaceFor(font)
                paint.textSize = size
                paint.measureText(string)
            }
        }

    private fun typefaceFor(font: OfficialFont): Typeface = typefaces.getOrPut(font) {
        runCatching { Typeface.createFromAsset(context.assets, font.assetPath) }
            .getOrDefault(Typeface.DEFAULT)
    }

    companion object {
        /** Beyond this the fit is more likely to be measurement noise than a real size difference. */
        private const val MAX_SIZE_CORRECTION = 0.35f

        /** Too few glyphs to tell families apart — one or two letters match almost anything. */
        private const val MIN_SAMPLE_CHARS = 4

        /**
         * Pure selection, with measurement passed in so the choice can be tested without a
         * Typeface. [measure] returns the width of a string in one font at one size, in points.
         */
        fun select(
            text: String,
            measuredWidthPt: Float,
            reportedSizePt: Float,
            measure: (OfficialFont, String, Float) -> Float
        ): DetectedFont {
            val sample = text.trim()
            val fallback = DetectedFont(OfficialFont.SANS, reportedSizePt, Float.MAX_VALUE)
            if (sample.length < MIN_SAMPLE_CHARS ||
                measuredWidthPt <= 0f ||
                reportedSizePt <= 0f
            ) {
                return fallback
            }

            val scored = OfficialFont.entries.mapNotNull { font ->
                val width = measure(font, sample, reportedSizePt)
                if (width <= 0f) null else font to width
            }
            val (best, bestWidth) = scored.minByOrNull { (_, width) ->
                abs(width - measuredWidthPt)
            } ?: return fallback

            val correction = measuredWidthPt / bestWidth
            // A correction beyond a third is not a font at a slightly different size, it is a run
            // whose width says nothing useful — letter-spacing, a stretched matrix, a partial line.
            val sizePt = if (abs(correction - 1f) <= MAX_SIZE_CORRECTION) {
                reportedSizePt * correction
            } else {
                reportedSizePt
            }

            return DetectedFont(
                font = best,
                sizePt = sizePt,
                widthError = abs(bestWidth - measuredWidthPt) / measuredWidthPt
            )
        }
    }
}
