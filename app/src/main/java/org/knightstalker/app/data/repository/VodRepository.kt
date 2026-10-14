// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.repository

import org.knightstalker.app.data.local.db.FavoriteDao
import org.knightstalker.app.data.remote.StalkerApi
import org.knightstalker.app.data.remote.StalkerAuthManager
import org.knightstalker.app.data.remote.XtreamCodesApi
import org.knightstalker.app.data.remote.dto.VodCategoryData
import org.knightstalker.app.data.remote.dto.VodData
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

    suspend fun getSeriesSeasons(baseUrl: String, seriesId: String): Result<List<Season>> {
        return when (portalType()) {
            // For Xtream, we don't have the episode list from the server's get_series_info.
            // Return an empty list — the UI will show "No episodes available" gracefully.
            PortalType.XTREAM -> {
                val portal = xtreamPortal()
                try {
                    val result = xtreamApi.getSeriesInfo(portal.url, portal.username, portal.password, seriesId)
                    result.onFailure { return Result.failure(it) }
                    val info = result.getOrThrow()
                    // The server returns a "seasons" list with metadata but no episodes.
                    // We return an empty list so the UI shows the series detail without episodes.
                    Result.success(emptyList())
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

    suspend fun createEpisodeLink(baseUrl: String, seasonCmd: String, episode: Int): Result<String> {
        return when (portalType()) {
            PortalType.XTREAM -> {
                val portal = xtreamPortal()
                Result.success(xtreamApi.buildSeriesUrl(portal.url, portal.username, portal.password, seasonCmd))
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
