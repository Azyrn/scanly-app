package com.skeler.scanely.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

const val THEME_SWITCH_DURATION_MILLIS = 220

@Composable
fun ColorScheme.animated(
    spec: AnimationSpec<Color> = tween(THEME_SWITCH_DURATION_MILLIS, easing = FastOutSlowInEasing)
): ColorScheme {
    @Composable
    fun Color.role(label: String): Color = animateColorAsState(this, spec, label = label).value
    return copy(
        primary = primary.role("primary"),
        onPrimary = onPrimary.role("onPrimary"),
        primaryContainer = primaryContainer.role("primaryContainer"),
        onPrimaryContainer = onPrimaryContainer.role("onPrimaryContainer"),
        inversePrimary = inversePrimary.role("inversePrimary"),
        secondary = secondary.role("secondary"),
        onSecondary = onSecondary.role("onSecondary"),
        secondaryContainer = secondaryContainer.role("secondaryContainer"),
        onSecondaryContainer = onSecondaryContainer.role("onSecondaryContainer"),
        tertiary = tertiary.role("tertiary"),
        onTertiary = onTertiary.role("onTertiary"),
        tertiaryContainer = tertiaryContainer.role("tertiaryContainer"),
        onTertiaryContainer = onTertiaryContainer.role("onTertiaryContainer"),
        background = background.role("background"),
        onBackground = onBackground.role("onBackground"),
        surface = surface.role("surface"),
        onSurface = onSurface.role("onSurface"),
        surfaceVariant = surfaceVariant.role("surfaceVariant"),
        onSurfaceVariant = onSurfaceVariant.role("onSurfaceVariant"),
        surfaceTint = surfaceTint.role("surfaceTint"),
        surfaceDim = surfaceDim.role("surfaceDim"),
        surfaceBright = surfaceBright.role("surfaceBright"),
        surfaceContainerLowest = surfaceContainerLowest.role("surfaceContainerLowest"),
        surfaceContainerLow = surfaceContainerLow.role("surfaceContainerLow"),
        surfaceContainer = surfaceContainer.role("surfaceContainer"),
        surfaceContainerHigh = surfaceContainerHigh.role("surfaceContainerHigh"),
        surfaceContainerHighest = surfaceContainerHighest.role("surfaceContainerHighest"),
        inverseSurface = inverseSurface.role("inverseSurface"),
        inverseOnSurface = inverseOnSurface.role("inverseOnSurface"),
        outline = outline.role("outline"),
        outlineVariant = outlineVariant.role("outlineVariant"),
        error = error.role("error"),
        onError = onError.role("onError"),
        errorContainer = errorContainer.role("errorContainer"),
        onErrorContainer = onErrorContainer.role("onErrorContainer"),
        primaryFixed = primaryFixed.role("primaryFixed"),
        primaryFixedDim = primaryFixedDim.role("primaryFixedDim"),
        onPrimaryFixed = onPrimaryFixed.role("onPrimaryFixed"),
        onPrimaryFixedVariant = onPrimaryFixedVariant.role("onPrimaryFixedVariant"),
        secondaryFixed = secondaryFixed.role("secondaryFixed"),
        secondaryFixedDim = secondaryFixedDim.role("secondaryFixedDim"),
        onSecondaryFixed = onSecondaryFixed.role("onSecondaryFixed"),
        onSecondaryFixedVariant = onSecondaryFixedVariant.role("onSecondaryFixedVariant"),
        tertiaryFixed = tertiaryFixed.role("tertiaryFixed"),
        tertiaryFixedDim = tertiaryFixedDim.role("tertiaryFixedDim"),
        onTertiaryFixed = onTertiaryFixed.role("onTertiaryFixed"),
        onTertiaryFixedVariant = onTertiaryFixedVariant.role("onTertiaryFixedVariant"),
    )
}

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
