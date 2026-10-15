// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.repository

import org.knightstalker.app.data.local.db.FavoriteDao
import org.knightstalker.app.data.remote.StalkerApi
import org.knightstalker.app.data.remote.StalkerAuthManager
import org.knightstalker.app.data.remote.XtreamCodesApi
import org.knightstalker.app.data.remote.dto.VodCategoryData
import org.knightstalker.app.data.remote.dto.VodData
import org.knightstalker.app.domain.model.EpisodeRef
import org.knightstalker.app.domain.model.PortalType
import org.knightstalker.app.domain.model.Season
import org.knightstalker.app.domain.model.VodCategory
import org.knightstalker.app.domain.model.VodItem

class VodRepository(
    private val api: StalkerApi,
    private val favoriteDao: FavoriteDao,
    private val authManager: StalkerAuthManager,
    private val xtreamApi: XtreamCodesApi
) {

    private fun portalType(): PortalType = authManager.currentPortal?.type ?: PortalType.STALKER

    private fun xtreamPortal() = authManager.currentPortal
        ?: throw Exception("No active portal")

    // -------------------------------------------------------------- Categories

    suspend fun getCategories(baseUrl: String): Result<List<VodCategory>> {
        return when (portalType()) {
            PortalType.XTREAM -> {
                val portal = xtreamPortal()
                try {
                    val cats = xtreamApi.getCategories(portal.url, portal.username, portal.password, "movie").getOrThrow()
                    Result.success(xtreamToVodCategories(cats))
                } catch (e: Exception) { Result.failure(e) }
            }
            PortalType.M3U -> Result.success(listOf(VodCategory(id = "*", title = "All", alias = "all")))
            else -> api.getVodCategories(baseUrl).map { response ->
                val categories = response.js ?: emptyList()
                val mapped = categories.map { it.toDomain() }
                val all = VodCategory(id = "*", title = "All", alias = "all")
                listOf(all) + mapped.filter { it.title.lowercase() != "all" }
            }
        }
    }

    suspend fun getSeriesCategories(baseUrl: String): Result<List<VodCategory>> {
        return when (portalType()) {
            PortalType.XTREAM -> {
                val portal = xtreamPortal()
                try {
                    val cats = xtreamApi.getCategories(portal.url, portal.username, portal.password, "series").getOrThrow()
                    Result.success(xtreamToVodCategories(cats))
                } catch (e: Exception) { Result.failure(e) }
            }
            PortalType.M3U -> Result.success(listOf(VodCategory(id = "*", title = "All", alias = "all")))
            else -> api.getSeriesCategories(baseUrl).map { response ->
                val categories = response.js ?: emptyList()
                val mapped = categories.map { it.toDomain() }
                val all = VodCategory(id = "*", title = "All", alias = "all")
                listOf(all) + mapped.filter { it.title.lowercase() != "all" }
            }
        }
    }

    // -------------------------------------------------------------- VOD list

    suspend fun getVodList(
        baseUrl: String,
        portalId: Long,
        categoryId: String? = null,
        page: Int = 1,
        search: String? = null,
        sortBy: String = "added"
    ): Result<Pair<List<VodItem>, Int>> {
        return when (portalType()) {
            PortalType.XTREAM -> {
                val portal = xtreamPortal()
                try {
                    val catId = if (categoryId == null || categoryId == "*") null else categoryId
                    val result = xtreamApi.getVodStreams(portal.url, portal.username, portal.password, catId)
                    result.onFailure { return Result.failure(it) }
                    var items = result.getOrThrow()
                    // Client-side search
                    if (!search.isNullOrBlank()) {
                        items = items.filter { it.name.contains(search, ignoreCase = true) }
                    }
                    val total = items.size
                    val start = (page - 1) * PAGE_SIZE
                    val pageItems = items.drop(start).take(PAGE_SIZE)
                    val favoriteIds = favoriteDao.getFavoriteIds(portalId).toSet()
                    val mapped = pageItems.map { item ->
                        VodItem(
                            id = item.stream_id,
                            name = item.name,
                            cmd = item.stream_id,
                            logo = item.image.ifBlank { null },
                            description = item.plot.ifBlank { null },
                            year = item.releasedate.ifBlank { item.releaseDate }.ifBlank { null },
                            genre = null,
                            rating = item.rating.ifBlank { null },
                            isFavorite = item.stream_id in favoriteIds
                        )
                    }
                    Result.success(mapped to total)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            PortalType.M3U -> Result.success(emptyList<VodItem>() to 0)
            else -> api.getVodList(baseUrl, categoryId, page, search, sortBy).map { response ->
                val data = response.js ?: return@map emptyList<VodItem>() to 0
                val favoriteIds = favoriteDao.getFavoriteIds(portalId).toSet()
                val items = data.data.map { it.toDomain(favoriteIds) }
                items to data.total_items
            }
        }
    }

    // -------------------------------------------------------------- Series list

    suspend fun getSeriesList(
        baseUrl: String,
        portalId: Long,
        categoryId: String? = null,
        page: Int = 1,
        search: String? = null,
        sortBy: String = "added"
    ): Result<Pair<List<VodItem>, Int>> {
        return when (portalType()) {
            PortalType.XTREAM -> {
                val portal = xtreamPortal()
                try {
                    val catId = if (categoryId == null || categoryId == "*") null else categoryId
                    val result = xtreamApi.getSeriesList(portal.url, portal.username, portal.password, catId)
                    result.onFailure { return Result.failure(it) }
                    var items = result.getOrThrow()
                    if (!search.isNullOrBlank()) {
                        items = items.filter { it.name.contains(search, ignoreCase = true) }
                    }
                    val total = items.size
                    val start = (page - 1) * PAGE_SIZE
                    val pageItems = items.drop(start).take(PAGE_SIZE)
                    val mapped = pageItems.map { item ->
                        VodItem(
                            id = item.series_id,
                            name = item.name,
                            cmd = item.series_id,
                            logo = item.image.ifBlank { null },
                            description = item.plot.ifBlank { null },
                            year = item.releasedate.ifBlank { item.releaseDate }.ifBlank { null },
                            genre = item.genre.ifBlank { null },
                            rating = item.rating.ifBlank { null }
                        )
                    }
                    Result.success(mapped to total)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            PortalType.M3U -> Result.success(emptyList<VodItem>() to 0)
            else -> api.getSeriesList(baseUrl, categoryId, page, search, sortBy).map { response ->
                val data = response.js ?: return@map emptyList<VodItem>() to 0
                val favoriteIds = favoriteDao.getFavoriteIds(portalId).toSet()
                val items = data.data.map { it.toDomain(favoriteIds) }
                items to data.total_items
            }
        }
    }

    // -------------------------------------------------------------- Links

    suspend fun createLink(
        baseUrl: String,
        cmd: String,
        name: String? = null,
        ext: String? = null
    ): Result<String> {
        return when (portalType()) {
            PortalType.XTREAM -> {
                val portal = xtreamPortal()
                // Pass the (URL-encoded) title so the Xtream URL uses the named
                // format, and the real container extension as reported by the
                // server — a forced `.mp4` is rejected on `action=` panels.
                Result.success(
                    xtreamApi.buildVodUrl(
                        portal.url,
                        portal.username,
                        portal.password,
                        cmd,
                        name,
                        ext?.takeIf { it.isNotBlank() } ?: "mp4"
                    )
                )
            }
            PortalType.M3U -> Result.success(cmd)
            else -> api.createVodLink(baseUrl, cmd).map { response ->
                val link = response.js?.cmd ?: throw Exception("No stream URL returned")
                if (link.startsWith("ffmpeg ")) link.substringAfter("ffmpeg ").trim()
                else link.trim()
            }
        }
    }

    /**
     * Last-resort per-episode stream refs for Xtream (seriesId -> episodeNum -> ref),
     * populated by [getSeriesSeasons]. Used to re-resolve an episode after the user
     * navigates away and back (the player route only carries the episode number).
     */
    private val xtreamEpisodeRefCache = HashMap<String, HashMap<Int, EpisodeRef>>()

    suspend fun getSeriesSeasons(baseUrl: String, seriesId: String): Result<List<Season>> {
        return when (portalType()) {
            PortalType.XTREAM -> {
                val portal = xtreamPortal()
                try {
                    val info = xtreamApi.getSeriesInfoParsed(portal.url, portal.username, portal.password, seriesId)
                        .getOrElse { throw it }

                    val episodesBySeason = info.episodes

                    // 1) Seasons that own at least one episode (the `episodes` map is
                    //    authoritative — some series omit it entirely).
                    val seasons = episodesBySeason.keys.mapNotNull { seasonKey ->
                        val eps = episodesBySeason[seasonKey].orEmpty()
                            .mapNotNull { e ->
                                val num = e.episode_num.toIntOrNull() ?: return@mapNotNull null
                                val ext = e.extension.ifBlank { "mp4" }
                                val ref = EpisodeRef(episodeId = e.id, ext = ext, title = e.title)
                                xtreamEpisodeRefCache
                                    .getOrPut(seriesId) { HashMap() }
                                    .put(num, ref)
                                num to ref
                            }.toMap()
                        if (eps.isEmpty()) return@mapNotNull null
                        val seasonNum = seasonKey.toIntOrNull() ?: 0
                        val poster = info.info?.cover?.takeIf { it.isNotBlank() }
                        Season(
                            id = seasonKey,
                            name = "Season $seasonNum",
                            cmd = seriesId,
                            episodes = eps.keys.sorted(),
                            poster = poster,
                            episodeStreams = eps
                        )
                    }

                    // 2) Merge in seasons that have metadata but no episodes yet
                    //    (e.g. the server listed a season with episode_count but no
                    //    episode rows) so the user still sees the season tab.
                    val known = seasons.map { it.id }.toSet()
                    val missing = info.seasons
                        .filter { it.seasonKey.isNotBlank() && it.seasonKey !in known }
                        .map { s ->
                            val count = s.episode_count.toIntOrNull() ?: 0
                            Season(
                                id = s.seasonKey,
                                name = s.name.ifBlank { "Season ${s.seasonKey.toIntOrNull() ?: 0}" },
                                cmd = seriesId,
                                episodes = if (count > 0) (1..count).toList() else emptyList(),
                                poster = s.cover.takeIf { it.isNotBlank() },
                                episodeStreams = emptyMap()
                            )
                        }

                    val all = (seasons + missing).sortedBy { it.id.toIntOrNull() ?: 0 }
                    Result.success(all)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            PortalType.M3U -> Result.success(emptyList())
            else -> api.getSeriesSeasons(baseUrl, seriesId).map { response ->
                (response.js?.data ?: emptyList()).map {
                    Season(
                        id = it.id,
                        name = it.name,
                        cmd = it.cmd,
                        episodes = it.episodes,
                        poster = it.screenshotUri.ifEmpty { null }
                    )
                }
            }
        }
    }

    /** Look up a previously crawled episode stream ref (Xtream), or null. */
    fun getEpisodeRef(seasonCmd: String, episode: Int): EpisodeRef? =
        xtreamEpisodeRefCache[seasonCmd]?.get(episode)

    suspend fun createEpisodeLink(
        baseUrl: String,
        seasonCmd: String,
        episode: Int,
        episodeRef: EpisodeRef? = null
    ): Result<String> {
        return when (portalType()) {
            PortalType.XTREAM -> {
                val portal = xtreamPortal()
                // Resolve the real per-episode stream id: prefer the explicit ref
                // (from the detail screen), fall back to the last crawl result.
                val ref = episodeRef
                    ?: xtreamEpisodeRefCache[seasonCmd]?.get(episode)
                    ?: return Result.failure(Exception("Episode stream id unknown — open the series and retry"))
                Result.success(xtreamApi.buildSeriesUrl(portal.url, portal.username, portal.password, ref.episodeId, ref.ext))
            }
            PortalType.M3U -> Result.success(seasonCmd)
            else -> api.createLink(baseUrl, seasonCmd, type = "vod", series = episode).map { response ->
                val link = response.js?.cmd ?: throw Exception("No stream URL returned")
                if (link.startsWith("ffmpeg ")) link.substringAfter("ffmpeg ").trim()
                else link.trim()
            }
        }
    }

    suspend fun createSeriesLink(baseUrl: String, cmd: String): Result<String> {
        return when (portalType()) {
            PortalType.XTREAM -> {
                val portal = xtreamPortal()
                Result.success(xtreamApi.buildSeriesUrl(portal.url, portal.username, portal.password, cmd))
            }
            PortalType.M3U -> Result.success(cmd)
            else -> api.createSeriesLink(baseUrl, cmd).map { response ->
                val link = response.js?.cmd ?: throw Exception("No stream URL returned")
                if (link.startsWith("ffmpeg ")) link.substringAfter("ffmpeg ").trim()
                else link.trim()
            }
        }
    }

    // -------------------------------------------------------------- Mappers

    private fun xtreamToVodCategories(cats: List<XtreamCodesApi.CategoryInfo>): List<VodCategory> {
        val mapped = cats.map { VodCategory(id = it.category_id, title = it.category_name, alias = it.category_name) }
        return listOf(VodCategory(id = "*", title = "All", alias = "all")) + mapped
    }

    private fun VodCategoryData.toDomain() = VodCategory(
        id = id,
        title = title,
        alias = alias
    )

    private fun VodData.toDomain(favoriteIds: Set<String>) = VodItem(
        id = id,
        name = name,
        cmd = cmd,
        logo = screenshotUri.ifEmpty { null },
        description = description.ifEmpty { null },
        year = year.ifEmpty { null },
        genre = genreStr.ifEmpty { null },
        rating = ratingImdb.ifEmpty { ratingKinopoisk.ifEmpty { null } },
        duration = time.ifEmpty { null },
        director = director.ifEmpty { null },
        actors = actors.ifEmpty { null },
        isFavorite = id in favoriteIds
    )

    companion object {
        private const val PAGE_SIZE = 60
    }
}
