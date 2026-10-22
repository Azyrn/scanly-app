package com.skeler.scanely.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skeler.scanely.auth.domain.repository.AuthRepository
import com.skeler.scanely.core.network.NetworkObserver
import com.skeler.scanely.history.data.HistoryManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ScanViewModel"

data class ScanUiState(
    val historyText: String? = null
)

@Singleton
class ScanStateHolder @Inject constructor() {
    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    fun update(transform: (ScanUiState) -> ScanUiState) {
        _uiState.update(transform)
    }

    fun reset() {
        _uiState.value = ScanUiState()
    }
}

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val stateHolder: ScanStateHolder,
    private val networkObserver: NetworkObserver,
    private val historyManager: HistoryManager,
    private val authRepository: AuthRepository
) : ViewModel() {

    val uiState: StateFlow<ScanUiState> = stateHolder.uiState

    private var historyItemId: String? = null

    val isOnline: StateFlow<Boolean> = networkObserver.isOnline
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    private val _showSignInPrompt = MutableStateFlow(false)

    val showSignInPrompt: StateFlow<Boolean> = _showSignInPrompt.asStateFlow()

    /** Checks sign-in only; the backend enforces tier-specific usage limits. */
    fun triggerAiWithRateLimit(onAllowed: () -> Unit): Boolean {
        if (!authRepository.isSignedIn) {
            _showSignInPrompt.value = true
            return false
        }
        onAllowed()
        return true
    }

    fun requireSignInForAi(): Boolean {
        if (authRepository.isSignedIn) return true
        _showSignInPrompt.value = true
        return false
    }

    fun dismissSignInPrompt() {
        _showSignInPrompt.value = false
    }

    fun onNewScanSelected() {
        historyItemId = null
        stateHolder.reset()
    }

    fun setHistoryText(text: String, id: String? = null) {
        historyItemId = id
        stateHolder.update {
            it.copy(historyText = text)
        }
    }

    fun updateHistoryText(newText: String) {
        stateHolder.update { it.copy(historyText = newText) }
        val id = historyItemId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                historyManager.updateItemText(id, newText)
            } catch (e: Exception) {
                Log.w(TAG, "History edit persist failed", e)
            }
        }
    }

    fun clearState() {
        historyItemId = null
        stateHolder.reset()
    }
}
