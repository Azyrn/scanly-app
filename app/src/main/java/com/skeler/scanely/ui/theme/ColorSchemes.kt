@file:SuppressLint("RestrictedApi")

package com.skeler.scanely.ui.theme

import android.annotation.SuppressLint
import androidx.annotation.FloatRange
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.utilities.Blend
import com.google.android.material.color.utilities.DynamicScheme
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.TonalPalette
import com.google.android.material.color.utilities.Variant
import kotlin.math.max

/**
 * Chroma per role. The primary stays as vivid as its seed (at least 48, like the old CorePalette a1),
 * secondary is a quiet pastel that supports it, tertiary is the brighter counter-accent, and the
 * neutrals carry a faint tint of the primary hue so surfaces feel warm rather than gray.
 */
private const val MIN_PRIMARY_CHROMA = 48.0
private const val SECONDARY_CHROMA = 24.0
private const val TERTIARY_CHROMA = 40.0
private const val NEUTRAL_CHROMA = 6.0
private const val NEUTRAL_VARIANT_CHROMA = 10.0

/** Light on-container text sits at tone 30 as in M3 Expressive, softer than the baseline tone 10. */
private const val EXPRESSIVE_ON_CONTAINER_TONE = 30.0

/**
 * Builds every role from Material's DynamicScheme, so contrast between pairs is solved by the
 * colour engine instead of a hand-picked tone table, and the fixed roles follow the seed too.
 */
fun seedColorScheme(seed: SeedColor, dark: Boolean): ColorScheme {
    val primaryHct = Hct.fromInt(seed.primary)
    val scheme = if (seed.monochrome) {
        val gray = TonalPalette.fromHueAndChroma(0.0, 0.0)
        DynamicScheme(primaryHct, Variant.MONOCHROME, dark, 0.0, gray, gray, gray, gray, gray)
    } else {
        val hue = primaryHct.hue
        DynamicScheme(
            primaryHct,
            Variant.VIBRANT,
            dark,
            0.0,
            TonalPalette.fromHueAndChroma(hue, max(primaryHct.chroma, MIN_PRIMARY_CHROMA)),
            TonalPalette.fromHueAndChroma(Hct.fromInt(seed.secondary).hue, SECONDARY_CHROMA),
            TonalPalette.fromHueAndChroma(Hct.fromInt(seed.tertiary).hue, TERTIARY_CHROMA),
            TonalPalette.fromHueAndChroma(hue, NEUTRAL_CHROMA),
            TonalPalette.fromHueAndChroma(hue, NEUTRAL_VARIANT_CHROMA),
        )
    }
    return scheme.toColorScheme(expressiveOnContainers = !dark && !seed.monochrome)
}

@Suppress("LongMethod")
private fun DynamicScheme.toColorScheme(expressiveOnContainers: Boolean): ColorScheme {
    fun c(argb: Int) = Color(argb)
    fun TonalPalette.onContainer(fallback: Int) =
        c(if (expressiveOnContainers) tone(EXPRESSIVE_ON_CONTAINER_TONE.toInt()) else fallback)

    return ColorScheme(
        primary = c(primary),
        onPrimary = c(onPrimary),
        primaryContainer = c(primaryContainer),
        onPrimaryContainer = primaryPalette.onContainer(onPrimaryContainer),
        inversePrimary = c(inversePrimary),
        secondary = c(secondary),
        onSecondary = c(onSecondary),
        secondaryContainer = c(secondaryContainer),
        onSecondaryContainer = secondaryPalette.onContainer(onSecondaryContainer),
        tertiary = c(tertiary),
        onTertiary = c(onTertiary),
        tertiaryContainer = c(tertiaryContainer),
        onTertiaryContainer = tertiaryPalette.onContainer(onTertiaryContainer),
        background = c(background),
        onBackground = c(onBackground),
        surface = c(surface),
        onSurface = c(onSurface),
        surfaceVariant = c(surfaceVariant),
        onSurfaceVariant = c(onSurfaceVariant),
        surfaceTint = c(surfaceTint),
        inverseSurface = c(inverseSurface),
        inverseOnSurface = c(inverseOnSurface),
        error = c(error),
        onError = c(onError),
        errorContainer = c(errorContainer),
        onErrorContainer = errorPalette.onContainer(onErrorContainer),
        outline = c(outline),
        outlineVariant = c(outlineVariant),
        scrim = c(scrim),
        surfaceBright = c(surfaceBright),
        surfaceDim = c(surfaceDim),
        surfaceContainer = c(surfaceContainer),
        surfaceContainerHigh = c(surfaceContainerHigh),
        surfaceContainerHighest = c(surfaceContainerHighest),
        surfaceContainerLow = c(surfaceContainerLow),
        surfaceContainerLowest = c(surfaceContainerLowest),
        primaryFixed = c(primaryFixed),
        primaryFixedDim = c(primaryFixedDim),
        onPrimaryFixed = c(onPrimaryFixed),
        onPrimaryFixedVariant = c(onPrimaryFixedVariant),
        secondaryFixed = c(secondaryFixed),
        secondaryFixedDim = c(secondaryFixedDim),
        onSecondaryFixed = c(onSecondaryFixed),
        onSecondaryFixedVariant = c(onSecondaryFixedVariant),
        tertiaryFixed = c(tertiaryFixed),
        tertiaryFixedDim = c(tertiaryFixedDim),
        onTertiaryFixed = c(onTertiaryFixed),
        onTertiaryFixedVariant = c(onTertiaryFixedVariant),
    )
}

/**
 * Pulls a fixed brand/category colour up to 15 degrees of hue toward [primary] (HCT harmonize),
 * so hand-picked accents sit inside whichever palette the user chose.
 */
fun Color.harmonizedWith(primary: Color): Color = Color(Blend.harmonize(toArgb(), primary.toArgb()))

fun ColorScheme.toOledBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = surfaceContainerLowest,
    surfaceContainer = surfaceContainerLow,
    surfaceContainerHigh = surfaceContainer,
    surfaceContainerHighest = surfaceContainerHigh,
)

private val DarkSurfaceFloor = Color(0xFF141218)

/**
 * Wallpaper (dynamic) dark schemes on some devices sit almost on pure black; this lifts them onto
 * a tinted ladder. Seed schemes get their dark surfaces from [seedColorScheme] and skip this.
 */
fun ColorScheme.enlivenDark(): ColorScheme {
    val base = if (surface.luminance() < 0.02f) DarkSurfaceFloor else surface

    fun rung(lift: Float, tintAmount: Float): Color =
        base.blend(Color.White, lift).blend(primary, tintAmount)

    val bg = rung(0.00f, 0.03f)
    return copy(
        background = bg,
        surface = bg,
        surfaceDim = bg,
        surfaceContainerLowest = rung(0.00f, 0.02f),
        surfaceContainerLow = rung(0.045f, 0.05f),
        surfaceContainer = rung(0.075f, 0.06f),
        surfaceContainerHigh = rung(0.11f, 0.07f),
        surfaceContainerHighest = rung(0.15f, 0.08f),
        surfaceBright = rung(0.18f, 0.06f),
    )
}

fun Color.blend(
    other: Color,
    @FloatRange(from = 0.0, to = 1.0) fraction: Float = 0.1f
): Color = Color(ColorUtils.blendARGB(this.toArgb(), other.toArgb(), fraction))
