package com.skeler.scanely.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@Stable
class ExtractedTextState internal constructor(initial: String) {
    var editing by mutableStateOf(false)
        internal set
    var draft by mutableStateOf(initial)
}
