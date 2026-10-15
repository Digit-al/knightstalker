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
import org.knightstalker.app.data.remote.TmdbApi
import org.knightstalker.app.data.repository.DownloadRepository
import org.knightstalker.app.data.repository.FavoriteRepository
import org.knightstalker.app.data.repository.MediaIndexRepository
import org.knightstalker.app.data.repository.SeriesRatingRepository
import org.knightstalker.app.data.repository.VodRepository
import org.knightstalker.app.data.repository.WatchHistoryRepository
import org.knightstalker.app.domain.model.EpisodeProgress
import org.knightstalker.app.domain.model.NextEpisode
import org.knightstalker.app.domain.model.Season
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
    val sortBy: VodSort = VodSort.Added,
    val syncStatus: String = "IDLE",   // IDLE | CRAWLING | DELTA | ERROR
    val itemsIndexed: Int = 0,
    val totalItemsRemote: Int = 0,
    /** True while TMDB detail enrichment is in flight for the selected VOD. */
    val detailsLoading: Boolean = false,
    // Seasons/episodes for the selected series (Series mode only).
    val seasons: List<Season> = emptyList(),
    val seasonsLoading: Boolean = false,
    val seasonsError: String? = null,
    // Per-season episode watch state (seasonCmd -> episode -> progress) and the
    // computed next episode to watch for the selected series.
    val episodeProgress: Map<String, Map<Int, EpisodeProgress>> = emptyMap(),
    val nextEpisode: NextEpisode? = null
)

class VodViewModel(
    private val vodRepository: VodRepository,
    private val favoriteRepository: FavoriteRepository,
    private val downloadRepository: DownloadRepository,
    private val authManager: StalkerAuthManager,
    private val preferences: UserPreferences,
    private val mediaIndexRepository: MediaIndexRepository,
    private val syncEngine: SyncEngine,
    private val watchHistoryRepository: WatchHistoryRepository,
    private val seriesRatingRepository: SeriesRatingRepository,
    private val tmdbApi: TmdbApi,
    private val mode: ContentMode = ContentMode.Vod
) : ViewModel() {

    private val _state = MutableStateFlow(VodState())
    val state: StateFlow<VodState> = _state.asStateFlow()

    val isSeries: Boolean = mode == ContentMode.Series

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
        val categoryId = if (cat == "*") null else cat

        // STALKER, XTREAM and M3U all serve the grid from the local Room index. For XTREAM
        // the index is built once by SyncEngine.fullCrawlXtream; for M3U by
        // SyncEngine.fullCrawlM3u (the in-memory playlist). This keeps scrolling off the
        // network — no repeated re-download / re-parse of the (potentially huge) list —
        // which is what used to OOM the process a few seconds after the catalogue
        // first appeared. The grid binds to Room from frame one; it fills in as the
        // background crawl indexes rows.
        val sortToken = when (_state.value.sortBy) {
            VodSort.Rating -> "RATING"
            VodSort.Year -> "YEAR"
            VodSort.Added -> "ADDED"
        }
        val excludeAdult = if (cat == "*") 1 else 0                              // "All" hides adult
        // Review = rated only, for movies. Series ratings are enriched lazily from TVmaze,
        // so keep unrated series visible (NULLs sort last) — otherwise they'd be hidden
        // here and never scroll into view to get fetched.
        //
        // M3U entries carry no rating (the playlist format has none), so a "rated only"
        // filter would hide the entire grid — keep them visible in every case.
        val isM3u = portal.type == org.knightstalker.app.domain.model.PortalType.M3U
        val ratedOnly = if (mode == ContentMode.Vod && _state.value.sortBy == VodSort.Rating && !isM3u) 1 else 0
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
        isFavorite = false,
        ext = ext,
        tmdbId = tmdbId
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
            } // ext defaults to "" — favorites store no container extension
            _state.update { it.copy(items = flowOf(PagingData.from(list))) }
        }
    }

    /**
     * Lazily fetch & cache a real TVmaze rating for a series as it scrolls into view.
     * No-op for movies and when there's no active portal; the repository dedupes and
     * throttles, and skips titles already checked.
     */
    fun ensureSeriesRating(vod: VodItem) {
        if (mode != ContentMode.Series) return
        val portalId = authManager.currentPortal?.id ?: return
        viewModelScope.launch {
            seriesRatingRepository.ensureRating(portalId, vod.id, vod.name, vod.year?.toIntOrNull())
        }
    }

    fun selectVod(vod: VodItem?) {
        _state.update { it.copy(selectedVod = vod, seasons = emptyList(), seasonsError = null) }
        // A top-level series has no directly-playable cmd; load its seasons so the
        // user can pick an episode instead of the player erroring on an empty cmd.
        if (vod != null && mode == ContentMode.Series) loadSeasons(vod.id)
        // For VOD: the Xtream `action=` endpoint only returns name/icon/rating/tmdb,
        // so enrich the detail from TMDB when a key is available.
        if (vod != null && mode == ContentMode.Vod) enrichVodDetails(vod)
    }

    /**
     * Fetch synopsis / cast / genres / runtime / release date from TMDB for the
     * selected VOD and merge them into [VodState.selectedVod]. No-op when the portal
     * supplied no TMDB id or no API key is configured. Runs once per id (cached).
     */
    private val tmdbCache = HashMap<String, VodItem>()
    private fun enrichVodDetails(vod: VodItem) {
        val tmdbId = vod.tmdbId
        if (tmdbId.isNullOrBlank() || tmdbId == "None") return
        if (!tmdbApi.isEnabled()) return
        // Already fetched in this session (either merged or previously failed).
        if (tmdbCache.containsKey(tmdbId)) return
        _state.update { it.copy(detailsLoading = true) }
        viewModelScope.launch {
            tmdbApi.movieDetails(tmdbId)
                .onSuccess { m ->
                    // Merge TMDB metadata over the sparse portal fields, but never
                    // clobber values the portal already provided with real data.
                    val merged = vod.copy(
                        description = m.overview.ifBlank { vod.description },
                        year = m.releaseYear().ifBlank { vod.year },
                        genre = m.genreNames().takeIf { it.isNotEmpty() }?.joinToString(", ") ?: vod.genre,
                        duration = m.runtime?.takeIf { it > 0 }?.let { "${it / 60} h ${it % 60} min" } ?: vod.duration,
                        director = m.director() ?: vod.director,
                        actors = m.castLine().ifBlank { vod.actors },
                        rating = vod.rating ?: m.vote_average?.takeIf { it > 0.0 }?.let { "%.1f".format(it) },
                        logo = vod.logo,
                        tmdbId = vod.tmdbId
                    )
                    tmdbCache[tmdbId] = merged
                    // Only apply if this VOD is still the one on screen.
                    _state.update { st ->
                        st.copy(
                            detailsLoading = false,
                            selectedVod = st.selectedVod?.takeIf { it.id == vod.id }?.let { merged }
                        )
                    }
                }
                .onFailure { e ->
                    android.util.Log.d("VodDetail", "TMDB enrichment failed for $tmdbId: ${e.message}")
                    tmdbCache[tmdbId] = vod // mark checked so we don't retry endlessly
                    _state.update { it.copy(detailsLoading = false) }
                }
        }
    }

    private fun loadSeasons(seriesId: String) {
        val portal = authManager.currentPortal ?: return
        _state.update { it.copy(seasonsLoading = true, seasonsError = null, episodeProgress = emptyMap(), nextEpisode = null) }
        viewModelScope.launch {
            vodRepository.getSeriesSeasons(portal.url, seriesId)
                .onSuccess { seasons ->
                    _state.update { it.copy(seasons = seasons, seasonsLoading = false) }
                    loadEpisodeProgress(seasons)
                }
                .onFailure { e ->
                    _state.update { it.copy(seasonsLoading = false, seasonsError = e.message) }
                }
        }
    }

    /** Load watched state for every season and compute the next episode to watch. */
    private fun loadEpisodeProgress(seasons: List<Season>) {
        val portalId = authManager.currentPortal?.id ?: return
        viewModelScope.launch {
            val progress = seasons.associate { season ->
                val entries = watchHistoryRepository.getSeasonProgress(portalId, season.cmd)
                season.cmd to entries.mapValues { (_, e) ->
                    EpisodeProgress(finished = e.isFinished, fraction = e.progressPercent)
                }
            }
            _state.update { it.copy(episodeProgress = progress, nextEpisode = computeNextEpisode(seasons, progress)) }
        }
    }

    // Resume the first in-progress episode; otherwise the first unwatched one.
    private fun computeNextEpisode(
        seasons: List<Season>,
        progress: Map<String, Map<Int, EpisodeProgress>>
    ): NextEpisode? {
        var firstUnwatched: NextEpisode? = null
        seasons.forEachIndexed { si, season ->
            val seasonLabel = "S${si + 1}"
            for (ep in season.episodes.sorted()) {
                val p = progress[season.cmd]?.get(ep)
                if (p != null && !p.finished && p.fraction > 0.02f) {
                    return NextEpisode(si, season.cmd, ep, season.episodes, "Resume $seasonLabel E$ep", resume = true, episodeRef = season.episodeStreams[ep])
                }
                if (firstUnwatched == null && (p == null || !p.finished)) {
                    firstUnwatched = NextEpisode(si, season.cmd, ep, season.episodes, "Play $seasonLabel E$ep", resume = false, episodeRef = season.episodeStreams[ep])
                }
            }
        }
        return firstUnwatched
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
            vodRepository.createLink(portal.url, vod.cmd, vod.name, vod.ext)
                .onSuccess { url ->
                    downloadRepository.startDownload(
                        id = "vod_${vod.id}_${System.currentTimeMillis()}",
                        name = vod.name,
                        streamUrl = url,
                        cmd = vod.cmd,
                        portalUrl = portal.url,
                        ext = vod.ext
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
        private val watchHistoryRepository: WatchHistoryRepository,
        private val seriesRatingRepository: SeriesRatingRepository,
        private val tmdbApi: TmdbApi,
        private val mode: ContentMode = ContentMode.Vod
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return VodViewModel(
                vodRepository, favoriteRepository, downloadRepository, authManager,
                preferences, mediaIndexRepository, syncEngine, watchHistoryRepository,
                seriesRatingRepository, tmdbApi, mode
            ) as T
        }
    }
}
