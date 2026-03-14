// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.livetv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.knightstalker.app.data.remote.StalkerAuthManager
import org.knightstalker.app.data.repository.ChannelRepository
import org.knightstalker.app.data.repository.EpgRepository
import org.knightstalker.app.data.repository.FavoriteRepository
import org.knightstalker.app.domain.model.Channel
import org.knightstalker.app.domain.model.ChannelCategory

data class LiveTvState(
    val categories: List<ChannelCategory> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val selectedCategory: String = "*",
    val isLoading: Boolean = true,
    val error: String? = null,
    val currentPage: Int = 1,
    val totalItems: Int = 0,
    val hasMore: Boolean = false
)

class LiveTvViewModel(
    private val channelRepository: ChannelRepository,
    private val epgRepository: EpgRepository,
    private val favoriteRepository: FavoriteRepository,
    private val authManager: StalkerAuthManager
) : ViewModel() {

    private val _state = MutableStateFlow(LiveTvState())
    val state: StateFlow<LiveTvState> = _state.asStateFlow()

    init {
        loadCategories()
        loadChannels()
    }

    private fun loadCategories() {
        val portal = authManager.currentPortal ?: return
        viewModelScope.launch {
            channelRepository.getCategories(portal.url)
                .onSuccess { cats ->
                    _state.update { it.copy(categories = cats) }
                }
        }
    }

    fun loadChannels(genreId: String? = null, page: Int = 1) {
        val portal = authManager.currentPortal ?: return
        _state.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            val categoryId = genreId ?: _state.value.selectedCategory
            channelRepository.getChannels(portal.url, portal.id, categoryId, page)
                .onSuccess { (channels, total) ->
                    val enriched = channels.map { channel ->
                        // Load short EPG for each channel
                        val epgResult = epgRepository.getShortEpg(portal.url, channel.id)
                        val epgEntries = epgResult.getOrNull() ?: emptyList()
                        val now = System.currentTimeMillis() / 1000
                        val nowPlaying = epgEntries.find { it.startTime <= now && it.endTime > now }
                        val upNext = epgEntries.find { it.startTime > now }
                        channel.copy(nowPlaying = nowPlaying, upNext = upNext)
                    }

                    _state.update {
                        it.copy(
                            channels = if (page == 1) enriched else it.channels + enriched,
                            isLoading = false,
                            currentPage = page,
                            totalItems = total,
                            hasMore = enriched.isNotEmpty() && (it.channels.size + enriched.size) < total,
                            selectedCategory = categoryId
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(isLoading = false, error = e.message) }
                }
        }
    }

    fun selectCategory(categoryId: String) {
        _state.update { it.copy(selectedCategory = categoryId, channels = emptyList()) }
        loadChannels(categoryId, 1)
    }

    fun loadMore() {
        if (_state.value.hasMore && !_state.value.isLoading) {
            loadChannels(page = _state.value.currentPage + 1)
        }
    }

    fun toggleFavorite(channel: Channel) {
        val portal = authManager.currentPortal ?: return
        viewModelScope.launch {
            favoriteRepository.toggleChannelFavorite(channel, portal.id)
            _state.update { state ->
                state.copy(
                    channels = state.channels.map {
                        if (it.id == channel.id) it.copy(isFavorite = !it.isFavorite) else it
                    }
                )
            }
        }
    }

    class Factory(
        private val channelRepository: ChannelRepository,
        private val epgRepository: EpgRepository,
        private val favoriteRepository: FavoriteRepository,
        private val authManager: StalkerAuthManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LiveTvViewModel(channelRepository, epgRepository, favoriteRepository, authManager) as T
        }
    }
}
