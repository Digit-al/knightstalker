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
import org.knightstalker.app.domain.model.EpgEntry
import java.text.SimpleDateFormat
import java.util.*

data class EpgState(
    val channels: List<Channel> = emptyList(),
    val epgData: Map<String, List<EpgEntry>> = emptyMap(),
    val selectedDate: String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()),
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
        loadChannelsAndEpg()
    }

    private fun loadChannelsAndEpg() {
        val portal = authManager.currentPortal ?: return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            channelRepository.getChannels(portal.url, portal.id, page = 1)
                .onSuccess { (channels, _) ->
                    _state.update { it.copy(channels = channels) }
                    // Load EPG for visible channels
                    channels.take(20).forEach { channel ->
                        loadEpgForChannel(channel.id)
                    }
                    _state.update { it.copy(isLoading = false) }
                }
                .onFailure { e ->
                    _state.update { it.copy(isLoading = false, error = e.message) }
                }
        }
    }

    fun loadEpgForChannel(channelId: String) {
        val portal = authManager.currentPortal ?: return
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
        loadChannelsAndEpg()
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
