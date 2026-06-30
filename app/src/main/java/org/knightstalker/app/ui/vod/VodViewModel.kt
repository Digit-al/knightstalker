// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.knightstalker.app.data.local.prefs.UserPreferences
import org.knightstalker.app.data.remote.ConnectionState
import org.knightstalker.app.data.remote.StalkerAuthManager
import org.knightstalker.app.data.repository.DownloadRepository
import org.knightstalker.app.data.repository.FavoriteRepository
import org.knightstalker.app.data.repository.VodRepository
import org.knightstalker.app.domain.model.VodCategory
import org.knightstalker.app.domain.model.VodItem

enum class ContentMode { Vod, Series }

/** Sort orders exposed in the Movies/Series header. [api] is the Stalker
 *  `get_ordered_list` sortby value; the portal returns these descending. */
enum class VodSort(val api: String, val label: String) {
    Added("added", "Date added"),
    Rating("rating", "Review"),
    Year("year", "Release date")
}

data class VodState(
    val categories: List<VodCategory> = emptyList(),
    val items: List<VodItem> = emptyList(),
    val selectedCategory: String = "*",
    val isLoading: Boolean = true,
    val error: String? = null,
    val currentPage: Int = 1,
    val totalItems: Int = 0,
    val hasMore: Boolean = false,
    val selectedVod: VodItem? = null,
    val favoriteCategoryIds: Set<String> = emptySet(),
    val searchQuery: String = "",
    val sortBy: VodSort = VodSort.Added,
    // Raw number of items fetched from the server so far (before any client-side
    // filtering), used to drive pagination independently of the visible count.
    val rawLoaded: Int = 0
)

class VodViewModel(
    private val vodRepository: VodRepository,
    private val favoriteRepository: FavoriteRepository,
    private val downloadRepository: DownloadRepository,
    private val authManager: StalkerAuthManager,
    private val preferences: UserPreferences,
    private val mode: ContentMode = ContentMode.Vod
) : ViewModel() {

    private val _state = MutableStateFlow(VodState())
    val state: StateFlow<VodState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            authManager.connectionState.collect { connState ->
                if (connState == ConnectionState.CONNECTED && _state.value.categories.isEmpty()) {
                    loadCategories()
                    loadVodList()
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
            val result = when (mode) {
                ContentMode.Vod -> vodRepository.getCategories(portal.url)
                ContentMode.Series -> vodRepository.getSeriesCategories(portal.url)
            }
            result.onSuccess { cats ->
                val favsCategory = VodCategory(id = "favs", title = "★ Favorites", alias = "favorites")
                _state.update { it.copy(categories = listOf(cats.first(), favsCategory) + cats.drop(1)) }
            }
        }
    }

    fun loadVodList(categoryId: String? = null, page: Int = 1) {
        val portal = authManager.currentPortal ?: return
        _state.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            val catId = categoryId ?: _state.value.selectedCategory
            val search = _state.value.searchQuery.takeIf { it.isNotBlank() }
            val sortBy = _state.value.sortBy.api
            val result = when (mode) {
                ContentMode.Vod -> vodRepository.getVodList(portal.url, portal.id, catId, page, search, sortBy)
                ContentMode.Series -> vodRepository.getSeriesList(portal.url, portal.id, catId, page, search, sortBy)
            }
            val sort = _state.value.sortBy
            result
                .onSuccess { (items, total) ->
                    // When sorting by Review, drop items the portal has no rating
                    // for — otherwise they sort to the very top as "N/A". Pagination
                    // tracks the raw server count so loading more keeps surfacing
                    // rated titles even when a page is mostly un-rated.
                    val display = if (sort == VodSort.Rating) {
                        items.filter { hasRating(it.rating) }
                    } else items
                    _state.update {
                        val rawLoaded = if (page == 1) items.size else it.rawLoaded + items.size
                        val accumulated = if (page == 1) display else it.items + display
                        // The portal ignores `sortby` once a category is selected, so
                        // enforce the order client-side for the fields we have.
                        it.copy(
                            items = sortItems(accumulated, sort),
                            isLoading = false,
                            currentPage = page,
                            totalItems = total,
                            rawLoaded = rawLoaded,
                            hasMore = items.isNotEmpty() && rawLoaded < total,
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
        if (categoryId == "favs") {
            loadFavoriteVod()
        } else {
            loadVodList(categoryId, 1)
        }
    }

    fun setSortBy(sort: VodSort) {
        if (_state.value.sortBy == sort) return
        _state.update { it.copy(sortBy = sort, items = emptyList(), rawLoaded = 0) }
        val cat = _state.value.selectedCategory
        if (cat == "favs") loadFavoriteVod() else loadVodList(cat, 1)
    }

    // A rating counts as present only if it parses to a positive number;
    // "N/A", "", "0" and other non-numeric values are treated as unrated.
    private fun hasRating(rating: String?): Boolean =
        rating?.trim()?.toFloatOrNull()?.let { it > 0f } ?: false

    // Client-side ordering, applied because the portal drops `sortby` for
    // category queries. Date added keeps the server's natural order (we have
    // no per-item added timestamp to sort by). Stable so equal keys keep their
    // server order.
    private fun sortItems(items: List<VodItem>, sort: VodSort): List<VodItem> = when (sort) {
        VodSort.Rating -> items.sortedByDescending { it.rating?.trim()?.toFloatOrNull() ?: 0f }
        VodSort.Year -> items.sortedByDescending { it.year?.trim()?.take(4)?.toIntOrNull() ?: 0 }
        VodSort.Added -> items
    }

    private var searchJob: Job? = null
    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(400)
            _state.update { it.copy(items = emptyList()) }
            loadVodList(page = 1)
        }
    }

    private fun loadFavoriteVod() {
        val portal = authManager.currentPortal ?: return
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            favoriteRepository.getFavoriteVod(portal.id).first().let { favorites ->
                val items = favorites.map { fav ->
                    VodItem(
                        id = fav.channelId,
                        name = fav.name,
                        cmd = fav.cmd,
                        logo = fav.logo,
                        isFavorite = true
                    )
                }
                _state.update {
                    it.copy(items = items, isLoading = false, hasMore = false, totalItems = items.size)
                }
            }
        }
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
                    downloadRepository.startDownload(
                        id = "vod_${vod.id}_${System.currentTimeMillis()}",
                        name = vod.name,
                        streamUrl = url,
                        cmd = vod.cmd,
                        portalUrl = portal.url
                    )
                }
        }
    }

    class Factory(
        private val vodRepository: VodRepository,
        private val favoriteRepository: FavoriteRepository,
        private val downloadRepository: DownloadRepository,
        private val authManager: StalkerAuthManager,
        private val preferences: UserPreferences,
        private val mode: ContentMode = ContentMode.Vod
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return VodViewModel(vodRepository, favoriteRepository, downloadRepository, authManager, preferences, mode) as T
        }
    }
}
