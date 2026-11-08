package com.skeler.scanely.ui.scan

import kotlin.math.abs
import kotlin.math.max

/** A point on a photo in normalised coordinates: (0, 0) is its top-left, (1, 1) its bottom-right. */
data class NormPoint(val x: Float, val y: Float) {
    fun clamped(): NormPoint = NormPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))

    /** The same spot after the photo turns a quarter clockwise. */
    fun turnedClockwise(): NormPoint = NormPoint(1f - y, x)
}

/**
 * A page outline on a photo: four corners clockwise from top-left, in [NormPoint] coordinates.
 * Only convex outlines exist — [of] refuses concave, self-intersecting and flat ones — so a warp
 * never receives a shape it cannot map onto a rectangle.
 */
@ConsistentCopyVisibility
data class NormQuad private constructor(val corners: List<NormPoint>) {

    /** This outline with corner [index] at [to] (kept on the photo), or null when that breaks convexity. */
    fun withCorner(index: Int, to: NormPoint): NormQuad? {
        if (index !in corners.indices) return null
        return of(corners.toMutableList().also { it[index] = to })
    }

    /** The outline on the photo turned a quarter clockwise; the corner now top-left comes first again. */
    fun turnedClockwise(): NormQuad {
        val turned = corners.map { it.turnedClockwise() }
        // The old bottom-left becomes the top-left.
        return NormQuad(listOf(turned[BL], turned[TL], turned[TR], turned[BR]))
    }

    fun turned(quarterTurns: Int): NormQuad {
        var q = this
        repeat(Math.floorMod(quarterTurns, QUARTERS)) { q = q.turnedClockwise() }
        return q
    }

    companion object {
        const val TL = 0
        const val TR = 1
        const val BR = 2
        const val BL = 3
        private const val QUARTERS = 4
        private const val MIN_TURN = 1e-6f

        /** The whole photo. */
        val FULL = NormQuad(listOf(NormPoint(0f, 0f), NormPoint(1f, 0f), NormPoint(1f, 1f), NormPoint(0f, 1f)))

        /** [points] clockwise from top-left (y down), kept on the photo, or null unless they make a convex, non-flat quad. */
        fun of(points: List<NormPoint>): NormQuad? {
            if (points.size != QUARTERS) return null
            val corners = points.map { it.clamped() }
            val turnsAllClockwise = corners.indices.all { i ->
                val a = corners[i]
                val b = corners[(i + 1) % QUARTERS]
                val c = corners[(i + 2) % QUARTERS]
                (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x) > MIN_TURN
            }
            // Four same-direction turns can only add up to one full turn, so this is also simple.
            return if (turnsAllClockwise) NormQuad(corners) else null
        }
    }
}

/** A rectangle in view pixels. */
data class ViewRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

/**
 * Where a photo of aspect [imageAspect] (width / height) lands in a [viewWidth] × [viewHeight] view.
 * [fill] = true is PreviewView FILL_CENTER (scaled to cover, centred, overflow cropped);
 * false is ContentScale.Fit (scaled to fit, centred).
 */
fun placeImage(imageAspect: Float, viewWidth: Float, viewHeight: Float, fill: Boolean): ViewRect {
    if (imageAspect <= 0f || viewWidth <= 0f || viewHeight <= 0f) return ViewRect(0f, 0f, viewWidth, viewHeight)
    val viewAspect = viewWidth / viewHeight
    val widthLimited = (imageAspect > viewAspect) != fill
    val w = if (widthLimited) viewWidth else viewHeight * imageAspect
    val h = if (widthLimited) viewWidth / imageAspect else viewHeight
    return ViewRect((viewWidth - w) / 2f, (viewHeight - h) / 2f, w, h)
}

fun ViewRect.toView(p: NormPoint): Pair<Float, Float> = (left + p.x * width) to (top + p.y * height)

fun ViewRect.toNorm(x: Float, y: Float): NormPoint =
    NormPoint((x - left) / max(width, 1f), (y - top) / max(height, 1f))

/**
 * The corner a touch at ([x], [y]) grabs: the nearest one whose square touch target (side [target], centred on
 * the corner) contains the touch, or null.
 */
fun ViewRect.cornerAt(quad: NormQuad, x: Float, y: Float, target: Float): Int? {
    val half = target / 2f
    return quad.corners.indices
        .map { i -> i to toView(quad.corners[i]) }
        .filter { (_, p) -> abs(p.first - x) <= half && abs(p.second - y) <= half }
        .minByOrNull { (_, p) -> (p.first - x) * (p.first - x) + (p.second - y) * (p.second - y) }
        ?.first
}

/**
 * The loupe's centre for a finger at ([x], [y]) on a [screenWidth] screen (spec 4, row 3c): [lift] above the
 * finger, flipped the same distance below it when it would cross the top margin, and kept [margin] from the
 * screen edges.
 */
fun loupeCentre(x: Float, y: Float, screenWidth: Float, radius: Float, lift: Float, margin: Float): Pair<Float, Float> {
    val above = y - lift
    val cy = if (above - radius < margin) y + lift else above
    val cx = x.coerceIn(margin + radius, max(margin + radius, screenWidth - margin - radius))
    return cx to cy
}
