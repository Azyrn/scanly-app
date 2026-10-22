package com.skeler.scanely.core.common

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.skeler.scanely.settings.data.SettingsKeys
import com.skeler.scanely.settings.presentation.viewmodel.SettingsViewModel

val LocalDarkMode = staticCompositionLocalOf<Boolean> {
    error("No dark mode provided")
}

const val MIN_READING_TEXT_SCALE = 0.8f
const val MAX_READING_TEXT_SCALE = 1.8f
const val READING_TEXT_SCALE_STEP = 0.1f

/** Reader-controlled font scale for extracted text, with the setter that persists it. */
@Immutable
class ReadingTextScale(
    val value: Float = 1f,
    val onChange: (Float) -> Unit = {}
) {
    val canGrow: Boolean get() = value < MAX_READING_TEXT_SCALE - 0.001f
    val canShrink: Boolean get() = value > MIN_READING_TEXT_SCALE + 0.001f

    fun grow() = onChange(step(value + READING_TEXT_SCALE_STEP))
    fun shrink() = onChange(step(value - READING_TEXT_SCALE_STEP))
    fun reset() = onChange(1f)

    private fun step(raw: Float): Float =
        (Math.round(raw * 10) / 10f).coerceIn(MIN_READING_TEXT_SCALE, MAX_READING_TEXT_SCALE)
}

val LocalReadingTextScale = staticCompositionLocalOf { ReadingTextScale() }

@Composable
fun CompositionLocals(
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    content: @Composable () -> Unit
) {
    val uiState by settingsViewModel.settingsUiState.collectAsState()
    // Wait for settings so first frame isn't default/flash.
    val state = uiState ?: return

    val isDarkTheme = state.resolvedDarkTheme(isSystemInDarkTheme())

    val textScale by settingsViewModel
        .getFloat(SettingsKeys.READING_TEXT_SCALE)
        .collectAsState(initial = 1f)

    CompositionLocalProvider(
        LocalSettings provides state,
        LocalDarkMode provides isDarkTheme,
        LocalReadingTextScale provides ReadingTextScale(textScale) {
            settingsViewModel.setFloat(SettingsKeys.READING_TEXT_SCALE, it)
        },
    ) {
        content()
    }
}
