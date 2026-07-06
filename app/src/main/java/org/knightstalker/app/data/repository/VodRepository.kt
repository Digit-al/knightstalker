// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.repository

import org.knightstalker.app.data.local.db.FavoriteDao
import org.knightstalker.app.data.remote.StalkerApi
import org.knightstalker.app.data.remote.dto.VodCategoryData
import org.knightstalker.app.data.remote.dto.VodData
import org.knightstalker.app.domain.model.Season
import org.knightstalker.app.domain.model.VodCategory
import org.knightstalker.app.domain.model.VodItem

class VodRepository(
    private val api: StalkerApi,
    private val favoriteDao: FavoriteDao
) {
    suspend fun getCategories(baseUrl: String): Result<List<VodCategory>> {
        return api.getVodCategories(baseUrl).map { response ->
            val categories = response.js ?: emptyList()
            val mapped = categories.map { it.toDomain() }
            val all = VodCategory(id = "*", title = "All", alias = "all")
            listOf(all) + mapped.filter { it.title.lowercase() != "all" }
        }
    }

    suspend fun getSeriesCategories(baseUrl: String): Result<List<VodCategory>> {
        return api.getSeriesCategories(baseUrl).map { response ->
            val categories = response.js ?: emptyList()
            val mapped = categories.map { it.toDomain() }
            val all = VodCategory(id = "*", title = "All", alias = "all")
            listOf(all) + mapped.filter { it.title.lowercase() != "all" }
        }
    }

    suspend fun getVodList(
        baseUrl: String,
        portalId: Long,
        categoryId: String? = null,
        page: Int = 1,
        search: String? = null,
        sortBy: String = "added"
    ): Result<Pair<List<VodItem>, Int>> {
        return api.getVodList(baseUrl, categoryId, page, search, sortBy).map { response ->
            val data = response.js ?: return@map emptyList<VodItem>() to 0
            val favoriteIds = favoriteDao.getFavoriteIds(portalId).toSet()
            val items = data.data.map { it.toDomain(favoriteIds) }
            items to data.total_items
        }
    }

    suspend fun getSeriesList(
        baseUrl: String,
        portalId: Long,
        categoryId: String? = null,
        page: Int = 1,
        search: String? = null,
        sortBy: String = "added"
    ): Result<Pair<List<VodItem>, Int>> {
        return api.getSeriesList(baseUrl, categoryId, page, search, sortBy).map { response ->
            val data = response.js ?: return@map emptyList<VodItem>() to 0
            val favoriteIds = favoriteDao.getFavoriteIds(portalId).toSet()
            val items = data.data.map { it.toDomain(favoriteIds) }
            items to data.total_items
        }
    }

    suspend fun createLink(baseUrl: String, cmd: String): Result<String> {
        return api.createVodLink(baseUrl, cmd).map { response ->
            val link = response.js?.cmd ?: throw Exception("No stream URL returned")
            if (link.startsWith("ffmpeg ")) link.substringAfter("ffmpeg ").trim()
            else link.trim()
        }
    }

    /** Seasons (with their episode-number lists) for a top-level series item. */
    suspend fun getSeriesSeasons(baseUrl: String, seriesId: String): Result<List<Season>> {
        return api.getSeriesSeasons(baseUrl, seriesId).map { response ->
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

    /** Mint a play URL for a single episode: the season cmd + episode number. */
    suspend fun createEpisodeLink(baseUrl: String, seasonCmd: String, episode: Int): Result<String> {
        return api.createLink(baseUrl, seasonCmd, type = "vod", series = episode).map { response ->
            val link = response.js?.cmd ?: throw Exception("No stream URL returned")
            if (link.startsWith("ffmpeg ")) link.substringAfter("ffmpeg ").trim()
            else link.trim()
        }
    }

    suspend fun createSeriesLink(baseUrl: String, cmd: String): Result<String> {
        return api.createSeriesLink(baseUrl, cmd).map { response ->
            val link = response.js?.cmd ?: throw Exception("No stream URL returned")
            if (link.startsWith("ffmpeg ")) link.substringAfter("ffmpeg ").trim()
            else link.trim()
        }
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
}
