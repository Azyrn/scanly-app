package com.skeler.scanely.core.pdfeditor.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rubs an eraser disc of [radius] at [centre] over this marking.
 *
 * Null when the disc misses, so the caller leaves the edit untouched; otherwise the surviving
 * markings, empty when the whole thing goes. Strokes and text bands split into pieces rather than
 * vanishing whole — rubbing a stroke's middle must leave both ends, or the tool is a delete button
 * wearing an eraser icon.
 *
 * Hit-testing is against real geometry, never a bounding box: a diagonal stroke's box is mostly
 * empty space, and erasing from that empty space is what makes an eraser feel broken.
 */
fun PdfEdit.Marking.eraseWith(centre: Offset, radius: Float): List<PdfEdit.Marking>? = when (this) {
    is PdfEdit.Ink -> {
        val survivors = strokes.flatMap { it.erase(centre, radius) }
        when {
            survivors == strokes -> null
            survivors.isEmpty() -> emptyList()
            else -> listOf(copy(strokes = survivors))
        }
    }

    is PdfEdit.Highlight -> {
        val kept = quads.filterNot { it.touches(centre, radius) }
        when {
            kept.size == quads.size -> null
            kept.isEmpty() -> emptyList()
            else -> listOf(copy(quads = kept))
        }
    }

    is PdfEdit.Underline -> {
        val kept = quads.filterNot { it.touches(centre, radius) }
        when {
            kept.size == quads.size -> null
            kept.isEmpty() -> emptyList()
            else -> listOf(copy(quads = kept))
        }
    }

    is PdfEdit.TextBox -> if (box.touches(centre, radius)) emptyList() else null
    is PdfEdit.ReplaceText -> if (cover.touches(centre, radius)) emptyList() else null
    is PdfEdit.Stamp -> if (box.touches(centre, radius)) emptyList() else null
    is PdfEdit.Shape -> if (touches(centre, radius)) emptyList() else null
}

/** Half the stroke counts as reach, so rubbing the visible edge of a fat stroke erases it. */
private fun InkStroke.erase(centre: Offset, radius: Float): List<InkStroke> {
    val pieces = points.cutAround(centre, radius + widthPt / 2f)
    return if (pieces.size == 1 && pieces.first() == points) {
        listOf(this)
    } else {
        pieces.map { copy(points = it) }
    }
}

/**
 * Splits a polyline wherever it passes within [radius] of [centre].
 *
 * Cuts at the exact circle crossing rather than dropping whole samples, so the cut lands under the
 * fingertip at any drawing speed — a fast stroke can put its samples several points apart, and
 * sample-level testing would either miss the stroke or take a visible bite out of it.
 */
private fun List<Offset>.cutAround(centre: Offset, radius: Float): List<List<Offset>> {
    if (isEmpty()) return emptyList()
    if (size == 1) {
        return if ((this[0] - centre).getDistance() <= radius) emptyList() else listOf(this)
    }

    val pieces = mutableListOf<List<Offset>>()
    var piece = mutableListOf<Offset>()

    fun closePiece() {
        if (piece.size >= 2) pieces += piece.toList()
        piece = mutableListOf()
    }

    for (index in 0 until size - 1) {
        val from = this[index]
        val to = this[index + 1]
        val covered = segmentInside(from, to, centre, radius)

        if (covered == null) {
            if (piece.isEmpty()) piece += from
            piece += to
            continue
        }

        val (enter, exit) = covered
        if (enter > 0f) {
            if (piece.isEmpty()) piece += from
            piece += pointOn(from, to, enter)
        }
        closePiece()
        if (exit < 1f) {
            piece += pointOn(from, to, exit)
            piece += to
        }
    }
    closePiece()
    return pieces
}

/** The `[enter, exit]` span of the segment lying inside the disc, or null when it stays clear. */
private fun segmentInside(
    from: Offset,
    to: Offset,
    centre: Offset,
    radius: Float
): Pair<Float, Float>? {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val lengthSquared = dx * dx + dy * dy
    val offsetX = from.x - centre.x
    val offsetY = from.y - centre.y

    if (lengthSquared < EPSILON) {
        return if (offsetX * offsetX + offsetY * offsetY <= radius * radius) 0f to 1f else null
    }

    val b = 2f * (offsetX * dx + offsetY * dy)
    val c = offsetX * offsetX + offsetY * offsetY - radius * radius
    val discriminant = b * b - 4f * lengthSquared * c
    if (discriminant < 0f) return null

    val root = sqrt(discriminant)
    val first = (-b - root) / (2f * lengthSquared)
    val second = (-b + root) / (2f * lengthSquared)
    val enter = max(0f, min(first, second))
    val exit = min(1f, max(first, second))
    return if (enter > exit) null else enter to exit
}

private fun PdfEdit.Shape.touches(centre: Offset, radius: Float): Boolean {
    val reach = radius + strokeWidthPt / 2f
    return when (kind) {
        ShapeKind.LINE, ShapeKind.ARROW ->
            distanceToSegment(centre, box.topLeft, Offset(box.right, box.bottom)) <= reach

        ShapeKind.RECTANGLE ->
            if (fillColor != null) box.touches(centre, radius) else box.outlineDistance(centre) <= reach

        ShapeKind.ELLIPSE ->
            if (fillColor != null && box.ellipseContains(centre)) {
                true
            } else {
                box.ellipseOutlineDistance(centre) <= reach
            }
    }
}

private fun pointOn(from: Offset, to: Offset, fraction: Float) =
    Offset(from.x + (to.x - from.x) * fraction, from.y + (to.y - from.y) * fraction)

private fun Rect.touches(centre: Offset, radius: Float): Boolean = distanceTo(centre) <= radius

/** Zero inside the rectangle, otherwise the gap to its nearest edge. */
private fun Rect.distanceTo(point: Offset): Float {
    val dx = max(max(left - point.x, 0f), point.x - right)
    val dy = max(max(top - point.y, 0f), point.y - bottom)
    return sqrt(dx * dx + dy * dy)
}

private fun Rect.outlineDistance(point: Offset): Float {
    val corners = listOf(
        Offset(left, top),
        Offset(right, top),
        Offset(right, bottom),
        Offset(left, bottom)
    )
    return corners.indices.minOf {
        distanceToSegment(point, corners[it], corners[(it + 1) % corners.size])
    }
}

private fun Rect.ellipseContains(point: Offset): Boolean {
    val radiusX = (width / 2f).coerceAtLeast(EPSILON)
    val radiusY = (height / 2f).coerceAtLeast(EPSILON)
    val normalisedX = (point.x - center.x) / radiusX
    val normalisedY = (point.y - center.y) / radiusY
    return normalisedX * normalisedX + normalisedY * normalisedY <= 1f
}

/** Sampled rather than solved: the closed form for distance to an ellipse is iterative anyway. */
private fun Rect.ellipseOutlineDistance(point: Offset): Float {
    val radiusX = (width / 2f).coerceAtLeast(EPSILON)
    val radiusY = (height / 2f).coerceAtLeast(EPSILON)
    var previous = Offset(center.x + radiusX, center.y)
    var best = Float.MAX_VALUE

    for (step in 1..ELLIPSE_SAMPLES) {
        val angle = TWO_PI * step / ELLIPSE_SAMPLES
        val current = Offset(
            center.x + radiusX * cos(angle),
            center.y + radiusY * sin(angle)
        )
        best = min(best, distanceToSegment(point, previous, current))
        previous = current
    }
    return best
}

private fun distanceToSegment(point: Offset, from: Offset, to: Offset): Float {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared < EPSILON) return (point - from).getDistance()

    val fraction = (((point.x - from.x) * dx + (point.y - from.y) * dy) / lengthSquared)
        .coerceIn(0f, 1f)
    return (point - Offset(from.x + dx * fraction, from.y + dy * fraction)).getDistance()
}

private const val EPSILON = 1e-6f
private const val ELLIPSE_SAMPLES = 64
private val TWO_PI = (2.0 * PI).toFloat()
