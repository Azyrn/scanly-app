package com.skeler.scanely.core.pdftools.office2pdf.pptx

/*
 * What the reader hands the renderer: one slide at a time, already flattened. Inheritance from
 * the layout and master, group transforms and theme colours are all resolved by the reader, so the
 * renderer never sees OOXML and the reader never touches android.graphics — which is what lets the
 * reader run in plain JVM tests. Every length here is in PDF points (1/72 inch), colours are ARGB.
 */

/** The deck: page size and the slide parts in show order. */
internal data class PptxPresentation(
    val widthPt: Float,
    val heightPt: Float,
    val slides: List<SlideRef>
)

/** [hidden] comes from a cheap look at the slide's root tag, so hidden slides are never parsed. */
internal data class SlideRef(val part: String, val hidden: Boolean)

/** Drawn in list order: master and layout decoration first, then the slide's own shapes. */
internal data class PptxSlide(val background: Fill?, val elements: List<SlideElement>)

/**
 * A box on the slide, with rotation (degrees, clockwise, about its centre) and flips applied in
 * the box's own space before the rotation, which is the order OOXML defines.
 */
internal data class Frame(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rotation: Float = 0f,
    val flipH: Boolean = false,
    val flipV: Boolean = false
)

internal sealed interface Fill {
    /** An explicit "no fill", which must stop inheritance — unlike a fill that is simply absent. */
    data object None : Fill

    data class Solid(val color: Int) : Fill

    /** Linear only; [angle] in degrees clockwise from left-to-right, [stops] sorted by position. */
    data class Gradient(val stops: List<GradientStop>, val angle: Float) : Fill

    /** A picture stretched over the box; [part] is the zip entry name. */
    data class Image(val part: String) : Fill
}

internal data class GradientStop(val position: Float, val color: Int)

internal data class Stroke(val color: Int, val width: Float, val dashed: Boolean = false)

/**
 * The preset shapes drawn exactly. Every other preset and every custom path is drawn as its
 * bounding [RECT], with a note, rather than not at all — the fill and text still land in place.
 */
internal enum class Geometry {
    RECT, ROUND_RECT, ELLIPSE, LINE, TRIANGLE, RIGHT_TRIANGLE, DIAMOND,
    ARROW_RIGHT, ARROW_LEFT, ARROW_UP, ARROW_DOWN
}

internal sealed interface SlideElement {
    val frame: Frame
}

/** [adjust] is the preset's first adjust value as a fraction (corner radius, apex, shaft). */
internal data class ShapeElement(
    override val frame: Frame,
    val geometry: Geometry,
    val adjust: Float?,
    val fill: Fill?,
    val stroke: Stroke?,
    val text: TextBody?
) : SlideElement

/** [crop] fractions are cut from each edge of the source image before it is stretched. */
internal data class PictureElement(
    override val frame: Frame,
    val part: String,
    val crop: Crop?,
    val geometry: Geometry,
    val stroke: Stroke?
) : SlideElement

internal data class Crop(val left: Float, val top: Float, val right: Float, val bottom: Float)

internal data class TableElement(
    override val frame: Frame,
    val columns: List<Float>,
    val rows: List<TableRow>
) : SlideElement

/** [height] is the minimum; rows grow to fit their text, as they do in PowerPoint. */
internal data class TableRow(val height: Float, val cells: List<TableCell>)

/** [covered] marks a cell hidden under a neighbour's span; it is never drawn. */
internal data class TableCell(
    val text: TextBody?,
    val fill: Fill?,
    val borders: CellBorders,
    val gridSpan: Int,
    val rowSpan: Int,
    val covered: Boolean
)

internal data class CellBorders(val left: Stroke?, val top: Stroke?, val right: Stroke?, val bottom: Stroke?)

internal enum class Anchor { TOP, CENTER, BOTTOM }

internal enum class TextAlign { LEFT, CENTER, RIGHT }

/** Only the three families every Android device has; anything else maps to the closest one. */
internal enum class FontKind { SANS, SERIF, MONO }

internal data class Insets(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** [fontScale] and [lineReduction] are normAutofit's shrink-on-overflow, already decided by PowerPoint. */
internal data class TextBody(
    val insets: Insets,
    val anchor: Anchor,
    val wrap: Boolean,
    val fontScale: Float,
    val lineReduction: Float,
    val paragraphs: List<Paragraph>
)

/**
 * [bullet] is the resolved marker text ("•", "3.", "c)"), or null. [endSize] sizes an empty
 * paragraph, which still takes a line in PowerPoint.
 */
internal data class Paragraph(
    val align: TextAlign,
    val marginLeft: Float,
    val indent: Float,
    val bullet: String?,
    val bulletColor: Int?,
    val lineSpacing: Float?,
    val runs: List<TextRun>,
    val endSize: Float
)

internal data class TextRun(
    val text: String,
    val size: Float,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val strike: Boolean,
    val color: Int,
    val font: FontKind
)

/**
 * What the conversion had to leave out or approximate, counted while reading and drawing, so the
 * result screen can say so instead of letting the user discover it.
 */
internal class PptxIssues {
    var charts = 0
    var diagrams = 0
    var embeddedObjects = 0
    var media = 0
    var missingImages = 0
    var shapesSimplified = false
    var fontsReplaced = false
}
