// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.epg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.knightstalker.app.data.remote.StalkerAuthManager
import org.knightstalker.app.data.repository.ChannelRepository
import org.knightstalker.app.data.repository.EpgRepository
import org.knightstalker.app.domain.model.Channel
import org.knightstalker.app.domain.model.ChannelCategory
import org.knightstalker.app.domain.model.EpgEntry
import java.text.SimpleDateFormat
import java.util.*

data class EpgState(
    val channels: List<Channel> = emptyList(),
    val epgData: Map<String, List<EpgEntry>> = emptyMap(),
    val selectedDate: String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()),
    val categories: List<ChannelCategory> = emptyList(),
    val selectedCategory: String? = null,  // null = "All"
    val isLoading: Boolean = true,
    val error: String? = null
)

class EpgViewModel(
    private val channelRepository: ChannelRepository,
    private val epgRepository: EpgRepository,
    private val authManager: StalkerAuthManager
) : ViewModel() {

    private val _state = MutableStateFlow(EpgState())
    val state: StateFlow<EpgState> = _state.asStateFlow()

    init {
        loadCategories()
        loadChannels()
    }

    private fun loadCategories() {
        val portal = authManager.currentPortal ?: return
        viewModelScope.launch {
            channelRepository.getCategories(portal.url)
                .onSuccess { cats -> _state.update { it.copy(categories = cats) } }
        }
    }

    /**
     * Loads ALL channels (paginating through the repository) for the currently
     * selected category.
     */
    private fun loadChannels() {
        val portal = authManager.currentPortal ?: return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val allChannels = mutableListOf<Channel>()
                var page = 1
                var hasMore = true
                while (hasMore) {
                    val result = channelRepository.getChannels(
                        baseUrl = portal.url,
                        portalId = portal.id,
                        genreId = _state.value.selectedCategory,
                        page = page
                    )
                    result
                        .onSuccess { (channels, total) ->
                            allChannels.addAll(channels)
                            // Guard against a bad total that would loop forever.
                            hasMore = channels.isNotEmpty() && allChannels.size < total
                            page++
                        }
                        .onFailure { e ->
                            hasMore = false
                            if (allChannels.isEmpty()) throw e
                        }
                }
                _state.update { it.copy(channels = allChannels, isLoading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    /**
     * Loads the EPG entries for a single channel (called lazily by EpgScreen
     * for each visible row).
     */
    fun loadEpgForChannel(channelId: String) {
        val portal = authManager.currentPortal ?: return
        if (channelId in _state.value.epgData) return
        viewModelScope.launch {
            epgRepository.getDayEpg(portal.url, channelId, _state.value.selectedDate)
                .onSuccess { entries ->
                    _state.update { state ->
                        state.copy(epgData = state.epgData + (channelId to entries))
                    }
                }
        }
    }

    fun selectDate(date: String) {
        _state.update { it.copy(selectedDate = date, epgData = emptyMap()) }
        loadChannels()
    }

    fun selectCategory(categoryId: String?) {
        _state.update { it.copy(selectedCategory = categoryId, epgData = emptyMap()) }
        loadChannels()
    }

    class Factory(
        private val channelRepository: ChannelRepository,
        private val epgRepository: EpgRepository,
        private val authManager: StalkerAuthManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return EpgViewModel(channelRepository, epgRepository, authManager) as T
        }
    }
}
