// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.knightstalker.app.data.remote.StalkerAuthManager
import org.knightstalker.app.data.repository.DownloadRepository
import org.knightstalker.app.data.repository.FavoriteRepository
import org.knightstalker.app.data.repository.VodRepository
import org.knightstalker.app.domain.model.VodCategory
import org.knightstalker.app.domain.model.VodItem

data class VodState(
    val categories: List<VodCategory> = emptyList(),
    val items: List<VodItem> = emptyList(),
    val selectedCategory: String = "*",
    val isLoading: Boolean = true,
    val error: String? = null,
    val currentPage: Int = 1,
    val totalItems: Int = 0,
    val hasMore: Boolean = false,
    val selectedVod: VodItem? = null
)

class VodViewModel(
    private val vodRepository: VodRepository,
    private val favoriteRepository: FavoriteRepository,
    private val downloadRepository: DownloadRepository,
    private val authManager: StalkerAuthManager
) : ViewModel() {

    private val _state = MutableStateFlow(VodState())
    val state: StateFlow<VodState> = _state.asStateFlow()

    init {
        loadCategories()
        loadVodList()
    }

    private fun loadCategories() {
        val portal = authManager.currentPortal ?: return
        viewModelScope.launch {
            vodRepository.getCategories(portal.url)
                .onSuccess { cats ->
                    _state.update { it.copy(categories = cats) }
                }
        }
    }

    fun loadVodList(categoryId: String? = null, page: Int = 1) {
        val portal = authManager.currentPortal ?: return
        _state.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            val catId = categoryId ?: _state.value.selectedCategory
            vodRepository.getVodList(portal.url, portal.id, catId, page)
                .onSuccess { (items, total) ->
                    _state.update {
                        it.copy(
                            items = if (page == 1) items else it.items + items,
                            isLoading = false,
                            currentPage = page,
                            totalItems = total,
                            hasMore = items.isNotEmpty() && (it.items.size + items.size) < total,
                            selectedCategory = catId
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(isLoading = false, error = e.message) }
                }
        }
    }

    fun selectCategory(categoryId: String) {
        _state.update { it.copy(selectedCategory = categoryId, items = emptyList()) }
        loadVodList(categoryId, 1)
    }

    fun loadMore() {
        if (_state.value.hasMore && !_state.value.isLoading) {
            loadVodList(page = _state.value.currentPage + 1)
        }
    }

    fun selectVod(vod: VodItem?) {
        _state.update { it.copy(selectedVod = vod) }
    }

    fun toggleFavorite(vod: VodItem) {
        val portal = authManager.currentPortal ?: return
        viewModelScope.launch {
            favoriteRepository.toggleVodFavorite(vod, portal.id)
            _state.update { state ->
                state.copy(
                    items = state.items.map {
                        if (it.id == vod.id) it.copy(isFavorite = !it.isFavorite) else it
                    },
                    selectedVod = state.selectedVod?.let {
                        if (it.id == vod.id) it.copy(isFavorite = !it.isFavorite) else it
                    }
                )
            }
        }
    }

    fun downloadVod(vod: VodItem) {
        val portal = authManager.currentPortal ?: return
        viewModelScope.launch {
            vodRepository.createLink(portal.url, vod.cmd)
                .onSuccess { url ->
                    downloadRepository.download(
                        id = "vod_${vod.id}_${System.currentTimeMillis()}",
                        name = vod.name,
                        streamUrl = url
                    )
                }
        }
    }

    class Factory(
        private val vodRepository: VodRepository,
        private val favoriteRepository: FavoriteRepository,
        private val downloadRepository: DownloadRepository,
        private val authManager: StalkerAuthManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return VodViewModel(vodRepository, favoriteRepository, downloadRepository, authManager) as T
        }
    }
}
