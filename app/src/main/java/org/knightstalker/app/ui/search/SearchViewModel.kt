// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.knightstalker.app.data.remote.StalkerAuthManager
import org.knightstalker.app.data.repository.ChannelRepository
import org.knightstalker.app.data.repository.VodRepository
import org.knightstalker.app.domain.model.Channel
import org.knightstalker.app.domain.model.VodItem

data class SearchState(
    val query: String = "",
    val channels: List<Channel> = emptyList(),
    val vod: List<VodItem> = emptyList(),
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false
)

class SearchViewModel(
    private val channelRepository: ChannelRepository,
    private val vodRepository: VodRepository,
    private val authManager: StalkerAuthManager
) : ViewModel() {

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChanged(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.length < 2) {
            _state.update { it.copy(channels = emptyList(), vod = emptyList(), hasSearched = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(500) // Debounce
            search(query)
        }
    }

    private suspend fun search(query: String) {
        val portal = authManager.currentPortal ?: return
        _state.update { it.copy(isLoading = true) }

        val channelsResult = channelRepository.getChannels(portal.url, portal.id, search = query)
        val vodResult = vodRepository.getVodList(portal.url, portal.id, search = query)

        _state.update {
            it.copy(
                channels = channelsResult.getOrNull()?.first ?: emptyList(),
                vod = vodResult.getOrNull()?.first ?: emptyList(),
                isLoading = false,
                hasSearched = true
            )
        }
    }

    class Factory(
        private val channelRepository: ChannelRepository,
        private val vodRepository: VodRepository,
        private val authManager: StalkerAuthManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SearchViewModel(channelRepository, vodRepository, authManager) as T
        }
    }
}
