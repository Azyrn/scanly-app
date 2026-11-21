package com.skeler.scanely.ui.components

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow

/**
 * A [TextFieldState] for a field whose text the caller owns as a String (usually a view model).
 * Typing reports through [onValueChange]; text the app sets itself — a cleared search, a key
 * loaded from storage — flows back into the field.
 */
@Composable
fun rememberSyncedTextFieldState(value: String, onValueChange: (String) -> Unit): TextFieldState {
    val field = rememberTextFieldState(value)
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    LaunchedEffect(field) {
        snapshotFlow { field.text.toString() }.collect { if (it != currentValue) currentOnValueChange(it) }
    }
    LaunchedEffect(field) {
        snapshotFlow { currentValue }.collect { if (it != field.text.toString()) field.setTextAndPlaceCursorAtEnd(it) }
    }
    return field
}
