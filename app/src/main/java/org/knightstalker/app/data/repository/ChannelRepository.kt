// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.repository

import org.knightstalker.app.data.local.db.FavoriteDao
import org.knightstalker.app.data.remote.StalkerApi
import org.knightstalker.app.data.remote.dto.ChannelData
import org.knightstalker.app.data.remote.dto.GenreData
import org.knightstalker.app.domain.model.Channel
import org.knightstalker.app.domain.model.ChannelCategory

class ChannelRepository(
    private val api: StalkerApi,
    private val favoriteDao: FavoriteDao
) {
    suspend fun getCategories(baseUrl: String): Result<List<ChannelCategory>> {
        return api.getGenres(baseUrl).map { response ->
            val genres = response.js ?: emptyList()
            val mapped = genres.map { it.toDomain() }
            val all = ChannelCategory(id = "*", title = "All", alias = "all")
            listOf(all) + mapped.filter { it.title.lowercase() != "all" }
        }
    }

    suspend fun getChannels(
        baseUrl: String,
        portalId: Long,
        genreId: String? = null,
        page: Int = 1,
        search: String? = null
    ): Result<Pair<List<Channel>, Int>> {
        return api.getChannels(baseUrl, genreId, page, search).map { response ->
            val data = response.js ?: return@map emptyList<Channel>() to 0
            val favoriteIds = favoriteDao.getFavoriteIds(portalId).toSet()
            val channels = data.data.map { it.toDomain(favoriteIds) }
            channels to data.total_items
        }
    }

    suspend fun createLink(baseUrl: String, cmd: String): Result<String> {
        return api.createLink(baseUrl, cmd).map { response ->
            val link = response.js?.cmd ?: throw Exception("No stream URL returned")
            extractUrl(link)
        }
    }

    private fun extractUrl(cmd: String): String {
        // cmd format: "ffmpeg http://..." or just the URL
        return if (cmd.startsWith("ffmpeg ")) {
            cmd.substringAfter("ffmpeg ").trim()
        } else if (cmd.startsWith("ffrt ")) {
            cmd.substringAfter("ffrt ").trim()
        } else {
            cmd.trim()
        }
    }

    private fun GenreData.toDomain() = ChannelCategory(
        id = id,
        title = title,
        alias = alias
    )

    private fun ChannelData.toDomain(favoriteIds: Set<String>) = Channel(
        id = id,
        name = name,
        number = number.toIntOrNull() ?: 0,
        cmd = cmd,
        logo = logo.ifEmpty { null },
        genre = null,
        genreId = tvGenreId,
        isFavorite = id in favoriteIds
    )
}
