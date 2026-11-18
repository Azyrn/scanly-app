package com.skeler.scanely.ui.scan

import android.net.Uri
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * Pages a [Handoff] scan finished with, waiting for the screen that asked for them. Activity-scoped: the scan
 * camera puts them here and pops back, and the caller [take]s them once.
 */
@HiltViewModel
class ScanHandBackViewModel @Inject constructor() : ViewModel() {
    private val _pending = MutableStateFlow<Map<String, List<Uri>>>(emptyMap())
    val pending: StateFlow<Map<String, List<Uri>>> = _pending.asStateFlow()

    fun deliver(key: String, pages: List<Uri>) = _pending.update { it + (key to pages) }

    /** The pages waiting under [key], removed so they are acted on once; null when there are none. */
    fun take(key: String): List<Uri>? {
        var taken: List<Uri>? = null
        _pending.update {
            taken = it[key]
            it - key
        }
        return taken
    }
}
