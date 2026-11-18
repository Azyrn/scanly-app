package com.skeler.scanely.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

@Stable
class ExtractedTextState internal constructor(initial: String) {
    var editing by mutableStateOf(false)
        internal set
    var draft by mutableStateOf(initial)
}

/**
 * Keeps a results screen's [ExtractedTextState] across configuration changes, so an edit in
 * progress survives rotation or a theme switch.
 *
 * A ViewModel rather than rememberSaveable: OCR of a long document is easily more text than the
 * ~1 MB Binder limit on the saved-instance-state Bundle, which would crash the app on the way to
 * the background. The state is rebuilt only when the result text itself changes — a new scan —
 * matching what the plain `remember(text)` it replaces did.
 */
class ExtractedTextHolder : ViewModel() {
    private var source: String? = null
    private var state: ExtractedTextState? = null

    fun stateFor(text: String): ExtractedTextState {
        val kept = state
        if (kept != null && source == text) return kept
        return ExtractedTextState(text).also {
            state = it
            source = text
        }
    }
}
