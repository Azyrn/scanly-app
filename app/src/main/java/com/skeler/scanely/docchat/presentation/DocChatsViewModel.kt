package com.skeler.scanely.docchat.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skeler.scanely.docchat.data.ChatEntity
import com.skeler.scanely.docchat.data.ChatRepository
import com.skeler.scanely.docchat.data.ChatSync
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The chat history: the cache at once, the server's changes on open and on pull to refresh. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class DocChatsViewModel @Inject internal constructor(
    private val repository: ChatRepository,
    private val sync: ChatSync
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    val refreshing: StateFlow<Boolean> = sync.refreshing

    /** Null until the cache has answered once, so an empty history is told from a loading one. */
    val chats: StateFlow<List<ChatEntity>?> = _query
        .debounce { if (it.isEmpty()) 0 else SEARCH_DEBOUNCE_MS }
        .flatMapLatest(repository::observeChats)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    init {
        refresh()
    }

    fun search(query: String) {
        _query.value = query.take(QUERY_LIMIT)
    }

    fun refresh() {
        viewModelScope.launch { sync.sync() }
    }

    fun rename(id: String, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch { repository.rename(id, title.take(TITLE_LIMIT)) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 150L
        const val STOP_TIMEOUT_MS = 5_000L
        const val QUERY_LIMIT = 100
        const val TITLE_LIMIT = 80
    }
}
