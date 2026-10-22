package com.skeler.scanely.core.pdfeditor.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import com.skeler.scanely.core.pdfeditor.engine.CompressionSettings
import com.skeler.scanely.core.pdfeditor.engine.PdfPermissions

/**
 * One staged change. Nothing here touches a file — an [EditSession] collects these and a single
 * save applies the whole list, which is what keeps a six-operation session to one write instead
 * of six save-and-reopen cycles.
 *
 * Coordinates are always in PDF points with the origin at the page's top-left, matching what the
 * renderer hands the canvas. The overlay composer flips to PDF's bottom-left origin on write.
 */
sealed interface PdfEdit {

    /** Edits that rearrange the document; applied before anything that draws on a page. */
    sealed interface Structural : PdfEdit

    /** Edits that paint onto a page; composited into a single overlay pass per page. */
    sealed interface Marking : PdfEdit {
        val pageIndex: Int
    }

    data class DeletePages(val pageIndices: Set<Int>) : Structural

    data class ReorderPages(val order: List<Int>) : Structural

    data class RotatePage(val pageIndex: Int, val degrees: Int) : Structural

    /** [box] is the retained region, in page points. */
    data class CropPage(val pageIndex: Int, val box: Rect) : Structural

    data class AppendDocument(val sourceUri: String, val pageSpec: String = "1-z") : Structural

    data class Compress(val settings: CompressionSettings) : Structural

    data class Encrypt(
        val userPassword: String,
        val ownerPassword: String,
        val permissions: PdfPermissions
    ) : Structural

    data object Decrypt : Structural

    data class StripMetadata(val stripXmp: Boolean) : Structural

    data class Ink(
        override val pageIndex: Int,
        val strokes: List<InkStroke>
    ) : Marking

    data class Highlight(
        override val pageIndex: Int,
        val quads: List<Rect>,
        val color: Color
    ) : Marking

    data class Underline(
        override val pageIndex: Int,
        val quads: List<Rect>,
        val color: Color,
        val style: LineStyle
    ) : Marking

    /**
     * Free text on the page. [baselinePt] pins the first line to an existing line of the document
     * when the text was inserted into one, and is null when it was simply dropped somewhere.
     */
    data class TextBox(
        override val pageIndex: Int,
        val box: Rect,
        val text: String,
        val font: OfficialFont,
        val sizePt: Float,
        val color: Color,
        val baselinePt: Float? = null
    ) : Marking

    /**
     * Cover-and-retype: [cover] is painted in [coverColor] over the original glyph run, then
     * [replacement] is drawn on top.
     *
     * [textBox] is where the new text is *laid out*, deliberately not [cover]. The cover hides the
     * old run's width; the new text may use the rest of the line out to the margin. Laying out
     * inside the cover wraps any longer replacement onto a second line over the line below, which
     * reads as the typed words having vanished.
     *
     * [baselinePt] is where the replaced glyphs actually sat. Laying out from the cover's top drops
     * the text by the glyph-box/line-box difference — visibly below its line, and out of the cover.
     */
    data class ReplaceText(
        override val pageIndex: Int,
        val cover: Rect,
        val coverColor: Color,
        val replacement: String,
        val font: OfficialFont,
        val sizePt: Float,
        val color: Color,
        val baselinePt: Float? = null,
        val textBox: Rect = cover
    ) : Marking

    data class Stamp(
        override val pageIndex: Int,
        val box: Rect,
        val imageId: String,
        val rotationDegrees: Float = 0f
    ) : Marking

    data class Shape(
        override val pageIndex: Int,
        val kind: ShapeKind,
        val box: Rect,
        val strokeColor: Color,
        val fillColor: Color?,
        val strokeWidthPt: Float
    ) : Marking
}

data class InkStroke(
    val points: List<Offset>,
    val color: Color,
    val widthPt: Float,
    /** Highlighter ink is drawn with multiply blending so underlying text stays readable. */
    val translucent: Boolean = false
)

enum class LineStyle { SOLID, SQUIGGLY, STRIKETHROUGH }

enum class ShapeKind { RECTANGLE, ELLIPSE, LINE, ARROW }

/**
 * Metric-compatible clones of the fonts official documents actually use, so a retyped line keeps
 * the original's width. All are OFL/Apache licensed and ship in assets/fonts, except LiberationSans:
 * pdfbox-android already ships a byte-identical copy that its FontMapperImpl must be able to load,
 * so the editor reads that one rather than the APK carrying the file twice.
 */
enum class OfficialFont(
    val displayName: String,
    val assetPath: String,
    val metricEquivalent: String
) {
    SERIF("Serif", "fonts/LiberationSerif-Regular.ttf", "Times New Roman"),
    SANS("Sans", "com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf", "Arial"),
    CALIBRI_LIKE("Calibri", "fonts/Carlito-Regular.ttf", "Calibri"),
    CAMBRIA_LIKE("Cambria", "fonts/Caladea-Regular.ttf", "Cambria"),
    MONO("Mono", "fonts/LiberationMono-Regular.ttf", "Courier New")
}
