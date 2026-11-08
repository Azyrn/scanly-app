@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.skeler.scanely.ui.theme

import android.app.Activity
import android.os.Build
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.skeler.scanely.core.common.LocalDarkMode
import com.skeler.scanely.core.common.LocalSettings

/**
 * Bar colours and contrast enforcement are no-ops from API 35, where `enableEdgeToEdge` owns them.
 * They still have to be cleared below that, or the scrim sits over the edge-to-edge surface.
 */
@Suppress("DEPRECATION")
private fun clearLegacyBarScrims(window: Window) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) return

    window.statusBarColor = Color.Transparent.toArgb()
    window.navigationBarColor = Color.Transparent.toArgb()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }
}

/**
 * The user's colour scheme — wallpaper (dynamic) or seed, OLED black when chosen — in its light or [dark]
 * variant. The app theme uses the system's choice; the scan camera and crop always the dark one (spec R3).
 */
@Composable
fun appColorScheme(dark: Boolean): ColorScheme {
    val context = LocalContext.current
    val settings = LocalSettings.current
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val useDynamic = settings.useDynamicColors && supportsDynamic
    val seed = remember(settings.seedColorIndex) { SeedPalettes.seedAt(settings.seedColorIndex) }

    val baseScheme = remember(seed, dark, useDynamic, context) {
        when {
            useDynamic && dark -> dynamicDarkColorScheme(context)
            useDynamic -> dynamicLightColorScheme(context)
            else -> seedColorScheme(seed, dark)
        }
    }

    return when {
        dark && settings.isOledModeEnabled -> baseScheme.toOledBlack()
        dark && useDynamic -> baseScheme.enlivenDark()
        else -> baseScheme
    }
}

@Composable
fun ScanelyTheme(
    content: @Composable () -> Unit
) {
    val darkTheme = LocalDarkMode.current
    val animatedColorScheme = appColorScheme(darkTheme).animated()

    // Transparent system bars so the animated Compose surface paints edge-to-edge without bar lag.
    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as Activity).window
        val useDarkSystemBarIcons = animatedColorScheme.background.luminance() > 0.5f
        SideEffect {
            window.decorView.setBackgroundColor(animatedColorScheme.background.toArgb())
            clearLegacyBarScrims(window)
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = useDarkSystemBarIcons
                isAppearanceLightNavigationBars = useDarkSystemBarIcons
            }
        }
    }

    MaterialExpressiveTheme(
        colorScheme = animatedColorScheme,
        typography = if (LocalConfiguration.current.locales[0].language == "ar") ArabicTypography else Typography,
        shapes = ScanelyShapes,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(animatedColorScheme.background)
        ) {
            content()
        }
    }
}
