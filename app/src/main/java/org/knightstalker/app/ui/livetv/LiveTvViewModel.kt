// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.livetv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.knightstalker.app.data.local.prefs.UserPreferences
import org.knightstalker.app.data.remote.ConnectionState
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
    val hasMore: Boolean = false,
    val favoriteCategoryIds: Set<String> = emptySet(),
    val searchQuery: String = ""
)

class LiveTvViewModel(
    private val channelRepository: ChannelRepository,
    private val epgRepository: EpgRepository,
    private val favoriteRepository: FavoriteRepository,
    private val authManager: StalkerAuthManager,
    private val preferences: UserPreferences
) : ViewModel() {

    private val _state = MutableStateFlow(LiveTvState())
    val state: StateFlow<LiveTvState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            authManager.connectionState.collect { connState ->
                Log.d("LiveTvVM", "connectionState=$connState, categories=${_state.value.categories.size}, portal=${authManager.currentPortal?.name}")
                if (connState == ConnectionState.CONNECTED && _state.value.categories.isEmpty()) {
                    Log.d("LiveTvVM", "Loading categories and channels")
                    loadCategories()
                    loadChannels()
                }
            }
        }
        viewModelScope.launch {
            preferences.favoriteCategories.collect { favs ->
                _state.update { it.copy(favoriteCategoryIds = favs) }
            }
        }
    }

    fun toggleFavoriteCategory(categoryId: String) {
        viewModelScope.launch { preferences.toggleFavoriteCategory(categoryId) }
    }

    private fun loadCategories() {
        val portal = authManager.currentPortal ?: return
        viewModelScope.launch {
            channelRepository.getCategories(portal.url)
                .onSuccess { cats ->
                    val favsCategory = ChannelCategory(id = "favs", title = "★ Favorites", alias = "favorites")
                    _state.update { it.copy(categories = listOf(cats.first(), favsCategory) + cats.drop(1)) }
                }
        }
    }

    fun loadChannels(genreId: String? = null, page: Int = 1) {
        val portal = authManager.currentPortal ?: return
        _state.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            val categoryId = genreId ?: _state.value.selectedCategory
            val search = _state.value.searchQuery.takeIf { it.isNotBlank() }
            channelRepository.getChannels(portal.url, portal.id, categoryId, page, search)
                .onSuccess { (rawChannels, total) ->
                    // Some portals ignore the search param for itv — filter client-side as a safety net
                    val channels = if (search != null) {
                        rawChannels.filter { it.name.contains(search, ignoreCase = true) }
                    } else rawChannels

                    // Enrich each channel with now/next EPG in parallel — sequential
                    // calls on a 50-channel page can take tens of seconds when the
                    // EPG feed is large, even though each lookup is a cache hit.
                    val enriched = if (channels.isEmpty()) {
                        emptyList()
                    } else {
                        coroutineScope {
                            channels.map { channel ->
                                async {
                                    try {
                                        val entries = epgRepository
                                            .getShortEpg(portal.url, channel.id)
                                            .getOrNull() ?: emptyList()
                                        val now = System.currentTimeMillis() / 1000
                                        val nowPlaying = entries.find { it.startTime <= now && it.endTime > now }
                                        val upNext = entries.find { it.startTime > now }
                                        channel.copy(nowPlaying = nowPlaying, upNext = upNext)
                                    } catch (e: Exception) {
                                        Log.w("LiveTvVM", "short EPG failed for ${channel.name}", e)
                                        channel
                                    }
                                }
                            }.awaitAll()
                        }
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
        if (categoryId == "favs") {
            loadFavoriteChannels()
        } else {
            loadChannels(categoryId, 1)
        }
    }

    private var searchJob: Job? = null
    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(400)
            _state.update { it.copy(channels = emptyList()) }
            loadChannels(page = 1)
        }
    }

    private fun loadFavoriteChannels() {
        val portal = authManager.currentPortal ?: return
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            favoriteRepository.getFavoriteChannels(portal.id).first().let { favorites ->
                val channels = favorites.map { fav ->
                    Channel(
                        id = fav.channelId,
                        name = fav.name,
                        number = 0,
                        cmd = fav.cmd,
                        logo = fav.logo,
                        genre = null,
                        genreId = "",
                        isFavorite = true
                    )
                }
                _state.update {
                    it.copy(
                        channels = channels,
                        isLoading = false,
                        hasMore = false,
                        totalItems = channels.size
                    )
                }
            }
        }
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
        private val authManager: StalkerAuthManager,
        private val preferences: UserPreferences
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LiveTvViewModel(channelRepository, epgRepository, favoriteRepository, authManager, preferences) as T
        }
    }
}
