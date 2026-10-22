package com.skeler.scanely.docchat.presentation.screen

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize

/**
 * The chat's one motion: short tweens on the standard curve. No springs, so nothing overshoots or
 * bounces, and no scaling, so nothing pops in like a bubble.
 */
internal object ChatMotion {
    const val DURATION_MS = 150

    fun <T> spec(): FiniteAnimationSpec<T> = tween(DURATION_MS, easing = FastOutSlowInEasing)
    fun color(): FiniteAnimationSpec<Color> = spec()
    fun size(): FiniteAnimationSpec<IntSize> = spec()

    fun fadeIn(): EnterTransition = fadeIn(spec())
    fun fadeOut(): ExitTransition = fadeOut(spec())
    fun crossfade(): ContentTransform = fadeIn() togetherWith fadeOut()
    fun expand(): EnterTransition = expandVertically(spec()) + fadeIn()
    fun shrink(): ExitTransition = shrinkVertically(spec()) + fadeOut()
}

/**
 * Sheets and the drawer read their motion from the theme: the chat's version is the same short
 * tween for every role, so opening and closing them is quick and never springs.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal val ChatMotionScheme: MotionScheme = object : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = tween(SPATIAL_MS, easing = FastOutSlowInEasing)
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = ChatMotion.spec()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = tween(SPATIAL_MS, easing = FastOutSlowInEasing)
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = ChatMotion.spec()
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = tween(EFFECTS_FAST_MS, easing = FastOutSlowInEasing)
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = ChatMotion.spec()
}

private const val SPATIAL_MS = 200
private const val EFFECTS_FAST_MS = 100
