package com.skeler.scanely.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme

/**
 * The app's motion: Material 3 Expressive's speeds, without its spatial overshoot. The expressive
 * spatial springs are underdamped (0.8 default, 0.6 fast), so a sheet opened by a button overshot
 * and every menu bounced in; a tap applies no force, so nothing it moves should bounce. Each spatial
 * spec keeps the expressive stiffness and becomes critically damped, still a spring, so a sheet or
 * drawer released by a drag carries on from the finger's speed and can be caught mid-flight.
 * Effects specs are already critically damped and pass through unchanged.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val ScanlyMotionScheme: MotionScheme = object : MotionScheme {
    private val base = MotionScheme.expressive()

    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = base.defaultSpatialSpec<T>().withoutBounce()
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = base.fastSpatialSpec<T>().withoutBounce()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = base.slowSpatialSpec<T>().withoutBounce()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = base.defaultEffectsSpec()
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = base.fastEffectsSpec()
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = base.slowEffectsSpec()
}

private fun <T> FiniteAnimationSpec<T>.withoutBounce(): FiniteAnimationSpec<T> =
    (this as? SpringSpec<T>)?.let { spring(Spring.DampingRatioNoBouncy, it.stiffness, it.visibilityThreshold) } ?: this
