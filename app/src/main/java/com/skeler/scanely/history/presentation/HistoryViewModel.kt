package com.skeler.scanely.history.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skeler.scanely.history.data.HistoryItem
import com.skeler.scanely.history.data.HistoryManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val historyManager: HistoryManager
) : ViewModel() {

    private val _historyItems = MutableStateFlow<List<HistoryItem>>(emptyList())
    val historyItems: StateFlow<List<HistoryItem>> = _historyItems.asStateFlow()

    init {
        loadHistory()
    }

    // HistoryManager reads, parses and rewrites a file of up to 50 OCR texts: never on the main thread.
    fun loadHistory() {
        viewModelScope.launch {
            _historyItems.value = withContext(Dispatchers.IO) { historyManager.getHistory() }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { historyManager.clearHistory() }
            _historyItems.value = emptyList()
        }
    }

    fun deleteItem(id: String) {
        viewModelScope.launch {
            _historyItems.value = withContext(Dispatchers.IO) {
                historyManager.deleteItem(id)
                historyManager.getHistory()
            }
        }
    }
}
