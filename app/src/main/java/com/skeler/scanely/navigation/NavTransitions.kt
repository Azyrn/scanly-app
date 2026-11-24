package com.skeler.scanely.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import kotlin.math.pow

val EaseOutExpo = Easing { fraction ->
    if (fraction == 1f) 1f else 1f - 2f.pow(-10f * fraction)
}

// Shared axis X where only the screen on top fades: the incoming one on a push, the outgoing one on
// a pop (NavHost draws the pushed screen above the one it covers). The screen underneath stays
// opaque. If both fade, there is a stretch where neither covers the window and the root background
// dominates the frame (a white flash in the light theme), held for as long as a predictive-back
// finger is down. The fade uses an ease-out so the change shows in the first frames.
// 250 ms, under the 300 ms UI ceiling: screens change tens of times a session.
private const val AXIS_DURATION_MS = 250
private const val FADE_DURATION_MS = 140

// Partial width so motion suggests edge without full viewport travel.
private const val AXIS_DISTANCE_FRACTION = 0.30f

private fun axisDistance(fullWidth: Int): Int =
    (fullWidth * AXIS_DISTANCE_FRACTION).toInt()

private val topFade = tween<Float>(FADE_DURATION_MS, easing = EaseOutExpo)

fun gallerySlideEnter(): EnterTransition = slideInHorizontally(
    animationSpec = tween(AXIS_DURATION_MS, easing = EaseOutExpo),
    initialOffsetX = { axisDistance(it) }
) + fadeIn(animationSpec = topFade)

fun gallerySlideExit(): ExitTransition = slideOutHorizontally(
    animationSpec = tween(AXIS_DURATION_MS, easing = EaseOutExpo),
    targetOffsetX = { -axisDistance(it) }
)

fun galleryPopEnter(): EnterTransition = slideInHorizontally(
    animationSpec = tween(AXIS_DURATION_MS, easing = EaseOutExpo),
    initialOffsetX = { -axisDistance(it) }
)

fun galleryPopExit(): ExitTransition = slideOutHorizontally(
    animationSpec = tween(AXIS_DURATION_MS, easing = EaseOutExpo),
    targetOffsetX = { axisDistance(it) }
) + fadeOut(animationSpec = topFade)

/*
 * Navigation 2.10 pops through a separate pair of transitions when the pop came from a back
 * gesture rather than from a tap, and defaults them to the platform's scale-the-screen-into-a-card
 * animation. Left in place, that default replaces the motion above with one nobody wrote, and the
 * same screen animates one way from its toolbar button and another way from a swipe.
 *
 * The gesture is given the same pop pair, so there is exactly one pop animation in the app. The
 * swipe edge is deliberately ignored: the shared axis has a direction of travel that belongs to
 * the navigation, not to which side of the screen the finger started on.
 */

fun galleryPredictivePopEnter(): EnterTransition = galleryPopEnter()

fun galleryPredictivePopExit(): ExitTransition = galleryPopExit()
