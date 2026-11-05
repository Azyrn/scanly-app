@file:SuppressLint("RestrictedApi")

package com.skeler.scanely.ui.theme

import android.annotation.SuppressLint
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.google.android.material.color.utilities.Hct
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedColorSchemeTest {

    private val schemes: List<Triple<SeedColor, Boolean, ColorScheme>> =
        SeedPalettes.ALL.flatMap { seed ->
            listOf(false, true).map { dark -> Triple(seed, dark, seedColorScheme(seed, dark)) }
        }

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun chroma(c: Color) = Hct.fromInt(c.toArgb()).chroma

    @Test
    fun everyTextPairIsReadable() {
        schemes.forEach { (seed, dark, s) ->
            val pairs = mapOf(
                "primary" to (s.onPrimary to s.primary),
                "primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
                "secondary" to (s.onSecondary to s.secondary),
                "secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
                "tertiary" to (s.onTertiary to s.tertiary),
                "tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
                "error" to (s.onError to s.error),
                "errorContainer" to (s.onErrorContainer to s.errorContainer),
                "surface" to (s.onSurface to s.surface),
                "surfaceVariantText" to (s.onSurfaceVariant to s.surfaceContainerHighest),
                "primaryOnSurface" to (s.primary to s.surface),
                "primaryFixed" to (s.onPrimaryFixed to s.primaryFixed),
            )
            pairs.forEach { (role, pair) ->
                val ratio = contrast(pair.first, pair.second)
                assertTrue("${seed.name} dark=$dark $role contrast $ratio", ratio >= 4.5)
            }
        }
    }

    @Test
    fun secondaryIsQuieterThanPrimary() {
        schemes.filterNot { it.first.monochrome }.forEach { (seed, dark, s) ->
            assertTrue(
                "${seed.name} dark=$dark",
                chroma(s.secondary) < chroma(s.primary),
            )
        }
    }

    @Test
    fun fixedRolesFollowTheSeedAndIgnoreDarkMode() {
        val baselinePurpleFixed = Color(0xFFEADDFF)
        SeedPalettes.ALL.filterNot { it == SeedPalettes.LavenderVolt }.forEach { seed ->
            val light = seedColorScheme(seed, dark = false)
            val dark = seedColorScheme(seed, dark = true)
            assertNotEquals(seed.name, baselinePurpleFixed, light.primaryFixed)
            assertTrue(seed.name, light.primaryFixedDim == dark.primaryFixedDim)
        }
    }

    @Test
    fun surfacesStepUpInOrder() {
        schemes.forEach { (seed, dark, s) ->
            val ladder = listOf(
                s.surfaceContainerLowest, s.surfaceContainerLow, s.surfaceContainer,
                s.surfaceContainerHigh, s.surfaceContainerHighest,
            ).map { it.luminance() }
            val ordered = if (dark) ladder == ladder.sorted() else ladder == ladder.sortedDescending()
            assertTrue("${seed.name} dark=$dark $ladder", ordered)
        }
    }
}
