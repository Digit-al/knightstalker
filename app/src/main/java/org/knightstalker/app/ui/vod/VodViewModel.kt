// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.ui.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.knightstalker.app.data.local.db.FtsQuery
import org.knightstalker.app.data.local.entity.MediaIndexRow
import org.knightstalker.app.data.local.prefs.UserPreferences
import org.knightstalker.app.data.local.sync.MediaKind
import org.knightstalker.app.data.local.sync.SyncEngine
import org.knightstalker.app.data.remote.ConnectionState
import org.knightstalker.app.data.remote.StalkerAuthManager
import org.knightstalker.app.data.repository.DownloadRepository
import org.knightstalker.app.data.repository.FavoriteRepository
import org.knightstalker.app.data.repository.MediaIndexRepository
import org.knightstalker.app.data.repository.VodRepository
import org.knightstalker.app.domain.model.VodCategory
import org.knightstalker.app.domain.model.VodItem

enum class ContentMode { Vod, Series }

/** Sort orders exposed in the Movies/Series header. [api] doubles as the stable
 *  id used by the sidebar dropdown; ordering is now served by the local index. */
enum class VodSort(val api: String, val label: String) {
    Added("added", "Date added"),
    Rating("rating", "Review"),
    Year("year", "Release date")
}

data class VodState(
    val categories: List<VodCategory> = emptyList(),
    val items: Flow<PagingData<VodItem>> = flowOf(PagingData.empty()),
    val selectedCategory: String = "*",
    val error: String? = null,
    val selectedVod: VodItem? = null,
    val favoriteCategoryIds: Set<String> = emptySet(),
    val searchQuery: String = "",
    val sortBy: VodSort = VodSort.Rating,
    val syncStatus: String = "IDLE",   // IDLE | CRAWLING | DELTA | ERROR
    val itemsIndexed: Int = 0,
    val totalItemsRemote: Int = 0
)

class VodViewModel(
    private val vodRepository: VodRepository,
    private val favoriteRepository: FavoriteRepository,
    private val downloadRepository: DownloadRepository,
    private val authManager: StalkerAuthManager,
    private val preferences: UserPreferences,
    private val mediaIndexRepository: MediaIndexRepository,
    private val syncEngine: SyncEngine,
    private val mode: ContentMode = ContentMode.Vod
) : ViewModel() {

    private val _state = MutableStateFlow(VodState())
    val state: StateFlow<VodState> = _state.asStateFlow()

    private val kind: MediaKind =
        if (mode == ContentMode.Vod) MediaKind.MOVIE else MediaKind.SERIES

    init {
        viewModelScope.launch {
            authManager.connectionState.collect { connState ->
                if (connState == ConnectionState.CONNECTED && _state.value.categories.isEmpty()) {
                    // loadCategories() lands on the configured default category and
                    // (re)builds the local-index pager. The grid binds to Room from
                    // frame one regardless of index size.
                    loadCategories()
                    rebuildPager()
                    syncEngine.ensureFresh(kind)
                    launch {
                        syncEngine.status(kind).collect { st ->
                            _state.update {
                                it.copy(
                                    syncStatus = st?.status ?: "IDLE",
                                    itemsIndexed = st?.itemsIndexed ?: 0,
                                    totalItemsRemote = st?.totalItemsRemote ?: 0
                                )
                            }
                        }
                    }
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
                val full = listOf(cats.first(), favsCategory) + cats.drop(1)
                _state.update { it.copy(categories = full) }
                // Land on the configured default category if it exists in this mode
                // (it's a movie category, so Series falls back to "All").
                val defaultName = preferences.defaultCategory.first()
                val target = normalizeName(defaultName)
                val match = full.firstOrNull { normalizeName(it.title) == target }
                    ?: full.firstOrNull { target.isNotEmpty() && normalizeName(it.title).contains(target) }
                if (match != null) selectCategory(match.id)
            }
        }
    }

    /** Build the Compose-facing PagingData flow from the local index for the
     *  current category / sort / search state. Browse never touches the network. */
    private fun rebuildPager() {
        val portal = authManager.currentPortal ?: run {
            // Cannot resolve a portal -> degrade gracefully to an empty grid. Emit an
            // explicit empty page (not emptyFlow(), which never emits and would leave the
            // grid stuck on a loading spinner) so VodScreen renders its empty state.
            _state.update { it.copy(items = flowOf(PagingData.empty())) }
            return
        }
        val cat = _state.value.selectedCategory
        if (cat == "favs") {
            loadFavoriteVod()
            return
        }

        val q = _state.value.searchQuery.trim()
        val sortToken = when (_state.value.sortBy) {
            VodSort.Rating -> "RATING"
            VodSort.Year -> "YEAR"
            VodSort.Added -> "ADDED"
        }
        val categoryId = if (cat == "*") null else cat
        val excludeAdult = if (cat == "*") 1 else 0                              // "All" hides adult
        val ratedOnly = if (_state.value.sortBy == VodSort.Rating) 1 else 0      // Review = rated only
        // Build the FTS string first, then derive hasQuery from it. A query made up
        // entirely of metacharacters (e.g. "***" or "():") sanitizes to "", which is an
        // invalid FTS MATCH expression and would crash SQLite. Treating an empty result
        // as "no query" keeps the grid showing the unfiltered list instead.
        val ftsQuery = FtsQuery.build(q)
        val hasQuery = if (ftsQuery.isNotBlank()) 1 else 0

        val flow = Pager(PagingConfig(pageSize = 60, enablePlaceholders = false)) {
            when (mode) {
                ContentMode.Vod -> mediaIndexRepository.pageMovies(
                    portal.id, categoryId, excludeAdult, ratedOnly, null, hasQuery, ftsQuery, sortToken
                )
                ContentMode.Series -> mediaIndexRepository.pageSeries(
                    portal.id, categoryId, excludeAdult, ratedOnly, null, hasQuery, ftsQuery, sortToken
                )
            }
        }.flow.map { data -> data.map { it.toVodItem() } }.cachedIn(viewModelScope)

        _state.update { it.copy(items = flow) }
    }

    private fun MediaIndexRow.toVodItem() = VodItem(
        id = itemId,
        name = name,
        cmd = cmd,
        logo = posterUrl.ifEmpty { null },
        year = year?.toString(),
        genre = genresCsv.ifEmpty { null },
        rating = rating?.toString(),
        duration = durationMin?.toString(),
        director = director.ifEmpty { null },
        actors = actors.ifEmpty { null },
        isFavorite = false
    )

    fun selectCategory(categoryId: String) {
        _state.update { it.copy(selectedCategory = categoryId) }
        rebuildPager()
    }

    fun setSortBy(sort: VodSort) {
        if (_state.value.sortBy == sort) return
        _state.update { it.copy(sortBy = sort) }
        rebuildPager()
    }

    // Strip everything but letters/digits and lowercase, so category titles that
    // differ only by spacing or pipe placement still compare equal.
    private fun normalizeName(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    private var searchJob: Job? = null
    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(400)
            rebuildPager()
        }
    }

    private fun loadFavoriteVod() {
        val portal = authManager.currentPortal ?: return
        viewModelScope.launch {
            val list = favoriteRepository.getFavoriteVod(portal.id).first().map { fav ->
                VodItem(
                    id = fav.channelId,
                    name = fav.name,
                    cmd = fav.cmd,
                    logo = fav.logo,
                    isFavorite = true
                )
            }
            _state.update { it.copy(items = flowOf(PagingData.from(list))) }
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
        private val mediaIndexRepository: MediaIndexRepository,
        private val syncEngine: SyncEngine,
        private val mode: ContentMode = ContentMode.Vod
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return VodViewModel(
                vodRepository, favoriteRepository, downloadRepository, authManager,
                preferences, mediaIndexRepository, syncEngine, mode
            ) as T
        }
    }
}
