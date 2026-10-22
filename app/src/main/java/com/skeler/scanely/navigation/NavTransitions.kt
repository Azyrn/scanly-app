package com.skeler.scanely.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import kotlin.math.pow

val EaseOutExpo = Easing { fraction ->
    if (fraction == 1f) 1f else 1f - 2f.pow(-10f * fraction)
}

// M3 shared-axis X: delayed fade-in avoids double-exposure blend.
private const val AXIS_DURATION_MS = 350
private const val FADE_IN_DURATION_MS = 250
private const val FADE_IN_DELAY_MS = 60
private const val FADE_OUT_DURATION_MS = 140

// Partial width so motion suggests edge without full viewport travel.
private const val AXIS_DISTANCE_FRACTION = 0.30f

private fun axisDistance(fullWidth: Int): Int =
    (fullWidth * AXIS_DISTANCE_FRACTION).toInt()

fun gallerySlideEnter(): EnterTransition = slideInHorizontally(
    animationSpec = tween(AXIS_DURATION_MS, easing = EaseOutExpo),
    initialOffsetX = { axisDistance(it) }
) + fadeIn(
    animationSpec = tween(
        FADE_IN_DURATION_MS,
        delayMillis = FADE_IN_DELAY_MS,
        easing = LinearOutSlowInEasing
    )
)

fun gallerySlideExit(): ExitTransition = slideOutHorizontally(
    animationSpec = tween(AXIS_DURATION_MS, easing = EaseOutExpo),
    targetOffsetX = { -axisDistance(it) }
) + fadeOut(
    animationSpec = tween(FADE_OUT_DURATION_MS, easing = FastOutLinearInEasing)
)

fun galleryPopEnter(): EnterTransition = slideInHorizontally(
    animationSpec = tween(AXIS_DURATION_MS, easing = EaseOutExpo),
    initialOffsetX = { -axisDistance(it) }
) + fadeIn(
    animationSpec = tween(
        FADE_IN_DURATION_MS,
        delayMillis = FADE_IN_DELAY_MS,
        easing = LinearOutSlowInEasing
    )
)

fun galleryPopExit(): ExitTransition = slideOutHorizontally(
    animationSpec = tween(AXIS_DURATION_MS, easing = EaseOutExpo),
    targetOffsetX = { axisDistance(it) }
) + fadeOut(
    animationSpec = tween(FADE_OUT_DURATION_MS, easing = FastOutLinearInEasing)
)

/*
 * Navigation 2.10 pops through a separate pair of transitions when the pop came from a back
 * gesture rather than from a tap, and defaults them to the platform's scale-the-screen-into-a-card
 * animation. Leaving that default in place is what replaced the motion above with one nobody
 * wrote, and it is also why the same screen animated one way from its toolbar button and another
 * way from a swipe.
 *
 * The gesture is given the same pop pair, so there is exactly one pop animation in the app. The
 * swipe edge is deliberately ignored: the shared axis has a direction of travel that belongs to
 * the navigation, not to which side of the screen the finger started on.
 */

fun galleryPredictivePopEnter(): EnterTransition = galleryPopEnter()

fun galleryPredictivePopExit(): ExitTransition = galleryPopExit()
