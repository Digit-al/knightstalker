// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.knightstalker.app.data.local.entity.FavoriteEntity
import org.knightstalker.app.data.remote.StalkerAuthManager
import org.knightstalker.app.data.repository.FavoriteRepository

data class FavoritesState(
    val channels: List<FavoriteEntity> = emptyList(),
    val vod: List<FavoriteEntity> = emptyList(),
    val selectedTab: Int = 0
)

class FavoritesViewModel(
    private val favoriteRepository: FavoriteRepository,
    private val authManager: StalkerAuthManager
) : ViewModel() {

    private val _state = MutableStateFlow(FavoritesState())
    val state: StateFlow<FavoritesState> = _state.asStateFlow()

    init {
        val portal = authManager.currentPortal ?: return
        viewModelScope.launch {
            favoriteRepository.getFavoriteChannels(portal.id).collect { channels ->
                _state.update { it.copy(channels = channels) }
            }
        }
        viewModelScope.launch {
            favoriteRepository.getFavoriteVod(portal.id).collect { vod ->
                _state.update { it.copy(vod = vod) }
            }
        }
    }

    fun selectTab(index: Int) {
        _state.update { it.copy(selectedTab = index) }
    }

    class Factory(
        private val favoriteRepository: FavoriteRepository,
        private val authManager: StalkerAuthManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FavoritesViewModel(favoriteRepository, authManager) as T
        }
    }
}
