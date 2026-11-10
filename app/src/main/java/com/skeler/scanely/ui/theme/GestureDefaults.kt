package com.skeler.scanely.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sign

/**
 * The one place gesture physics is tuned. Every drag in the app follows the same two phases:
 *
 * - **Track** while the finger is down: the element is the finger, 1:1, written in a layout or
 *   graphicsLayer lambda so no frame recomposes. Past a bound it rubber-bands ([RubberBand]).
 * - **Release** when it lifts: a spring seeded with the finger's velocity finishes the motion
 *   ([release] when the gesture goes through, [snapBack] when it doesn't). A new touch stops the
 *   spring where it is and tracking carries on from there.
 *
 * Only a drag release may bounce: the finger put force into it. The same element moved by a tap,
 * a button or code settles with [settle], which never overshoots.
 *
 * Compose already scales every animation by the system animator duration scale (WindowRecomposer
 * reads Settings.Global.ANIMATOR_DURATION_SCALE). When that scale is 0 — "Remove animations" —
 * [reducedMotion] also drops the bounce and the release velocity; tracking itself stays, because
 * the person is producing that motion.
 */
object GestureDefaults {
    /** Initial slope of the rubber band: past a bound the element moves a fifth of the finger. */
    const val RUBBER_BAND = 0.2f

    /** A slow drag commits once it has covered this fraction of the travel. */
    const val DISMISS_DISTANCE_FRACTION = 0.45f

    /** A release faster than this commits regardless of distance. Equal to M3's swipe-to-dismiss and AnchoredDraggable threshold. */
    val FlickVelocity = 125.dp

    /** Pressed elements shrink to this, starting on touch-down. */
    const val PRESS_SCALE = 0.97f

    /** Zoom past its range rubber-bands over this much scale, so 1× pinched in can never reach 0.5×. */
    const val ZOOM_BAND = 0.5f

    /** A long-pressed page tile becomes a reorder drag once the finger has moved this far. */
    val ReorderSlop = 8.dp

    /** A lifted (long-pressed or dragged) tile grows to this scale ... */
    const val LIFT_SCALE = 1.04f

    /** ... and fades to this alpha, so the slot beneath it reads through. */
    const val LIFTED_ALPHA = 0.85f

    /** Every target a gesture fix touches is at least this big. */
    val MinTouchTarget = 48.dp

    /** Damping of a drag release: a small overshoot that reads as momentum, not a wobble. */
    private const val RELEASE_DAMPING = 0.82f

    /** Release that commits (dismiss, next mode, drop into a slot). */
    private const val RELEASE_STIFFNESS = Spring.StiffnessMediumLow

    /** Return to where the drag started: stiffer, so it never looks like the UI arguing back slowly. */
    private const val SNAP_BACK_STIFFNESS = Spring.StiffnessMedium

    /** Taps, buttons, code: critically damped, never overshoots. */
    private const val SETTLE_STIFFNESS = Spring.StiffnessMediumLow

    /** Press scale in and out: critically damped and fast, so it reads as immediate. */
    private const val PRESS_STIFFNESS = Spring.StiffnessMedium

    fun <T> release(reducedMotion: Boolean, visibilityThreshold: T? = null): SpringSpec<T> = spring(
        dampingRatio = if (reducedMotion) Spring.DampingRatioNoBouncy else RELEASE_DAMPING,
        stiffness = RELEASE_STIFFNESS,
        visibilityThreshold = visibilityThreshold
    )

    fun <T> snapBack(reducedMotion: Boolean, visibilityThreshold: T? = null): SpringSpec<T> = spring(
        dampingRatio = if (reducedMotion) Spring.DampingRatioNoBouncy else RELEASE_DAMPING,
        stiffness = SNAP_BACK_STIFFNESS,
        visibilityThreshold = visibilityThreshold
    )

    fun <T> settle(visibilityThreshold: T? = null): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = SETTLE_STIFFNESS,
        visibilityThreshold = visibilityThreshold
    )

    fun <T> press(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = PRESS_STIFFNESS)

    /** The velocity a release spring starts with: the finger's, unless motion is reduced. */
    fun releaseVelocity(velocity: Float, reducedMotion: Boolean): Float = if (reducedMotion) 0f else velocity

    /** True when the system animator duration scale is 0 (developer setting or "Remove animations"). */
    fun reducedMotion(context: Context): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}

/** The rubber band past a drag's bounds, with [GestureDefaults.RUBBER_BAND] as its initial slope. */
object RubberBand {
    /**
     * Where an element shows when the finger is [overshoot] px past its bound, for a gesture area
     * [dimension] px long. Starts at [GestureDefaults.RUBBER_BAND] of the finger and stiffens the further it goes,
     * never reaching [dimension] (the curve iOS scroll views use).
     */
    fun rubberBand(overshoot: Float, dimension: Float): Float {
        if (overshoot == 0f || dimension <= 0f) return 0f
        val x = abs(overshoot)
        return sign(overshoot) * (1f - 1f / (x * GestureDefaults.RUBBER_BAND / dimension + 1f)) * dimension
    }

    /** The finger overshoot that [rubberBand] shows as [shown]: lets a caught element resume without a jump. */
    fun rubberBandInverse(shown: Float, dimension: Float): Float {
        if (shown == 0f || dimension <= 0f) return 0f
        val y = abs(shown).coerceAtMost(dimension * MAX_BAND_FRACTION)
        return sign(shown) * (dimension / GestureDefaults.RUBBER_BAND) * (y / (dimension - y))
    }

    /** [value] with anything outside [min]..[max] rubber-banded over [dimension]. */
    fun banded(value: Float, min: Float, max: Float, dimension: Float): Float = when {
        value < min -> min + rubberBand(value - min, dimension)
        value > max -> max + rubberBand(value - max, dimension)
        else -> value
    }

    /** Inverse of [banded]. */
    fun unbanded(shown: Float, min: Float, max: Float, dimension: Float): Float = when {
        shown < min -> min + rubberBandInverse(shown - min, dimension)
        shown > max -> max + rubberBandInverse(shown - max, dimension)
        else -> shown
    }

    private const val MAX_BAND_FRACTION = 0.999f
}
