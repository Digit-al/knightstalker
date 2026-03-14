// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.knightstalker.app.data.local.entity.WatchHistoryEntity
import org.knightstalker.app.data.remote.StalkerAuthManager
import org.knightstalker.app.data.repository.WatchHistoryRepository

data class HistoryState(
    val continueWatching: List<WatchHistoryEntity> = emptyList(),
    val allHistory: List<WatchHistoryEntity> = emptyList(),
    val selectedTab: Int = 0 // 0 = Continue Watching, 1 = All History
)

class HistoryViewModel(
    private val historyRepository: WatchHistoryRepository,
    private val authManager: StalkerAuthManager
) : ViewModel() {

    private val _state = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = _state.asStateFlow()

    init {
        val portal = authManager.currentPortal ?: return
        viewModelScope.launch {
            historyRepository.getContinueWatching(portal.id).collect { items ->
                _state.update { it.copy(continueWatching = items) }
            }
        }
        viewModelScope.launch {
            historyRepository.getHistory(portal.id).collect { items ->
                _state.update { it.copy(allHistory = items) }
            }
        }
    }

    fun selectTab(index: Int) {
        _state.update { it.copy(selectedTab = index) }
    }

    fun removeEntry(entry: WatchHistoryEntity) {
        val portal = authManager.currentPortal ?: return
        viewModelScope.launch {
            historyRepository.deleteEntry(entry.itemId, portal.id)
        }
    }

    fun clearAll() {
        val portal = authManager.currentPortal ?: return
        viewModelScope.launch {
            historyRepository.clearHistory(portal.id)
        }
    }

    class Factory(
        private val historyRepository: WatchHistoryRepository,
        private val authManager: StalkerAuthManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HistoryViewModel(historyRepository, authManager) as T
        }
    }
}
