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
import org.knightstalker.app.data.repository.EpgService
import kotlinx.coroutines.flow.first
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
    val error: String? = null,
    // Status of the portal's XMLTV feed (M3U/Xtream portals) so the UI can tell
    // "guide still loading" apart from "no programmes".
    val epgFeedLoading: Boolean = false,
    val epgFeedError: String? = null,
    val epgFeedChannels: Int = 0,
    val epgFeedEntries: Int = 0
)

class EpgViewModel(
    private val channelRepository: ChannelRepository,
    private val epgRepository: EpgRepository,
    private val authManager: StalkerAuthManager,
    private val epgService: EpgService? = null
) : ViewModel() {

    private val _state = MutableStateFlow(EpgState())
    val state: StateFlow<EpgState> = _state.asStateFlow()

    init {
        loadCategories()
        loadChannels()
        observeEpgFeedStatus()
    }

    /**
     * Mirrors the XMLTV feed status into [EpgState] so the UI can show
     * "Loading guide…" / the error instead of a misleading "No program info".
     *
     * When the feed flips from loading to loaded (or error) we also re-query
     * every channel that was still pending — those are the rows whose first
     * query ran before the feed was ready and therefore returned an empty
     * result we deliberately did not cache.
     */
    private fun observeEpgFeedStatus() {
        val service = epgService ?: return
        var wasLoading = false
        viewModelScope.launch {
            service.status.collect { s ->
                val justFinished = wasLoading && !s.isLoading
                _state.update {
                    it.copy(
                        epgFeedLoading = s.isLoading,
                        epgFeedError = s.error,
                        epgFeedChannels = s.channelCount,
                        epgFeedEntries = s.entryCount
                    )
                }
                wasLoading = s.isLoading
                if (justFinished) retryPendingChannels()
            }
        }
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
     *
     * Race-condition guard: when the result comes back empty AND the XMLTV
     * feed is still loading, the empty result is NOT cached — the feed was
     * simply not ready yet. The channel stays in "pending" state and the
     * retry loop re-queries once the feed status flips to loaded (see
     * [observeEpgFeedStatus]) or we give up after a bounded number of retries.
     */
    private val pendingChannels = mutableSetOf<String>()

    fun loadEpgForChannel(channelId: String) {
        val portal = authManager.currentPortal ?: return
        if (channelId in _state.value.epgData) return
        if (channelId in pendingChannels) return
        pendingChannels += channelId
        viewModelScope.launch {
            val result = epgRepository.getDayEpg(portal.url, channelId, _state.value.selectedDate)
            result
                .onSuccess { entries ->
                    // Authoritative feed status, read directly from the service
                    // (the state mirror is async and can lag behind).
                    val feedReady = epgService?.status?.value?.let { !it.isLoading }
                        ?: _state.value.epgFeedLoading.not()
                    if (entries.isEmpty() && !feedReady) {
                        // Feed not ready yet — leave the channel pending; the
                        // feed-status observer will retry it once loading ends.
                        pendingChannels -= channelId
                        return@launch
                    }
                    pendingChannels -= channelId
                    _state.update { state ->
                        state.copy(epgData = state.epgData + (channelId to entries))
                    }
                }
                .onFailure {
                    pendingChannels -= channelId
                }
        }
    }

    /**
     * Called when the XMLTV feed status flips to loaded: re-queries every
     * channel that was pending (queried before the feed was ready) and had
     * therefore not been cached.
     */
    private fun retryPendingChannels() {
        val portal = authManager.currentPortal ?: return
        val toRetry = pendingChannels.toList()
        if (toRetry.isEmpty()) return
        toRetry.forEach { pendingChannels -= it }
        toRetry.forEach { channelId ->
            viewModelScope.launch {
                epgRepository.getDayEpg(portal.url, channelId, _state.value.selectedDate)
                    .onSuccess { entries ->
                        _state.update { state ->
                            state.copy(epgData = state.epgData + (channelId to entries))
                        }
                    }
            }
        }
    }

    fun selectDate(date: String) {
        pendingChannels.clear()
        _state.update { it.copy(selectedDate = date, epgData = emptyMap()) }
        loadChannels()
    }

    fun selectCategory(categoryId: String?) {
        pendingChannels.clear()
        _state.update { it.copy(selectedCategory = categoryId, epgData = emptyMap()) }
        loadChannels()
    }

    /**
     * Force-refresh: drop the cached EPG for the current channels and reload,
     * also re-triggering the XMLTV feed download (used by the UI "retry" button
     * when the guide failed to load).
     */
    fun refreshAll() {
        val portal = authManager.currentPortal ?: return
        pendingChannels.clear()
        _state.update { it.copy(epgData = emptyMap(), epgFeedLoading = true, epgFeedError = null) }
        viewModelScope.launch {
            epgService?.ensureFeed(portal.id, force = true)
            _state.value.channels.forEach { ch ->
                epgRepository.getDayEpg(portal.url, ch.id, _state.value.selectedDate)
                    .onSuccess { entries ->
                        _state.update { s ->
                            s.copy(epgData = s.epgData + (ch.id to entries))
                        }
                    }
            }
        }
    }

    class Factory(
        private val channelRepository: ChannelRepository,
        private val epgRepository: EpgRepository,
        private val authManager: StalkerAuthManager,
        private val epgService: EpgService? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return EpgViewModel(channelRepository, epgRepository, authManager, epgService) as T
        }
    }
}
