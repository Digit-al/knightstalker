// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.repository

import org.knightstalker.app.data.local.db.FavoriteDao
import org.knightstalker.app.data.remote.StalkerApi
import org.knightstalker.app.data.remote.StalkerAuthManager
import org.knightstalker.app.data.remote.XtreamCodesApi
import org.knightstalker.app.data.remote.dto.ChannelData
import org.knightstalker.app.data.remote.dto.GenreData
import org.knightstalker.app.data.remote.parser.M3uChannel
import org.knightstalker.app.domain.model.Channel
import org.knightstalker.app.domain.model.ChannelCategory
import org.knightstalker.app.domain.model.PortalType

class ChannelRepository(
    private val api: StalkerApi,
    private val favoriteDao: FavoriteDao,
    private val authManager: StalkerAuthManager,
    private val xtreamApi: XtreamCodesApi
) {
    suspend fun getCategories(baseUrl: String): Result<List<ChannelCategory>> {
        val type = authManager.currentPortal?.type
        return when (type) {
            PortalType.M3U -> {
                val groups = authManager.m3uChannels
                    .mapNotNull { it.groupTitle }
                    .distinct()
                    .sorted()
                val all = ChannelCategory(id = "*", title = "All", alias = "all")
                Result.success(
                    listOf(all) + groups.map { ChannelCategory(id = it, title = it, alias = it) }
                )
            }
            PortalType.XTREAM -> {
                val cats = authManager.xtreamCategories
                val all = ChannelCategory(id = "*", title = "All", alias = "all")
                Result.success(listOf(all) + cats.map { it.toDomain() })
            }
            else -> api.getGenres(baseUrl).map { response ->
                val genres: List<GenreData> = response.js ?: emptyList()
                val mapped: List<ChannelCategory> = genres.map { it.toDomain() }
                val all = ChannelCategory(id = "*", title = "All", alias = "all")
                listOf(all) + mapped.filter { it.title.lowercase() != "all" }
            }
        }
    }

    suspend fun getChannels(
        baseUrl: String,
        portalId: Long,
        genreId: String? = null,
        page: Int = 1,
        search: String? = null
    ): Result<Pair<List<Channel>, Int>> {
        val type = authManager.currentPortal?.type ?: PortalType.STALKER
        val favoriteIds = favoriteDao.getFavoriteIds(portalId).toSet()

        return when (type) {
            PortalType.M3U -> {
                val all: List<M3uChannel> = authManager.m3uChannels
                val filtered: List<M3uChannel> = if (genreId != null && genreId != "*") {
                    all.filter { it.groupTitle == genreId }
                } else all
                val searched: List<M3uChannel> = if (!search.isNullOrBlank()) {
                    filtered.filter { it.name.contains(search, ignoreCase = true) }
                } else filtered
                val start = (page - 1) * PAGE_SIZE
                val pageItems: List<M3uChannel> = searched.drop(start).take(PAGE_SIZE)
                val channels: List<Channel> = pageItems.map { ch ->
                    Channel(
                        id = ch.id,
                        name = ch.name,
                        number = 0,
                        cmd = ch.url,
                        logo = ch.logo?.takeIf { it.isNotBlank() },
                        genre = ch.groupTitle,
                        genreId = ch.groupTitle,
                        epgKey = ch.tvgId,
                        isFavorite = ch.id in favoriteIds
                    )
                }
                Result.success(channels to searched.size)
            }
            PortalType.XTREAM -> {
                val portal = authManager.currentPortal
                    ?: return Result.failure(Exception("No portal"))
                val streams: List<XtreamCodesApi.StreamInfo> =
                    if (genreId != null && genreId != "*") {
                        xtreamApi.getLiveCategory(
                            portal.url, portal.username, portal.password, genreId
                        ).getOrThrow()
                    } else {
                        authManager.xtreamStreams
                    }
                val searched: List<XtreamCodesApi.StreamInfo> = if (!search.isNullOrBlank()) {
                    streams.filter { it.name.contains(search, ignoreCase = true) }
                } else streams
                val start = (page - 1) * PAGE_SIZE
                val pageItems: List<XtreamCodesApi.StreamInfo> = searched.drop(start).take(PAGE_SIZE)
                val channels: List<Channel> = pageItems.map { ch ->
                    Channel(
                        id = ch.stream_id,
                        name = ch.name,
                        number = ch.num,
                        cmd = ch.stream_id,
                        logo = ch.stream_icon.takeIf { it.isNotBlank() },
                        genre = null,
                        genreId = null,
                        epgKey = ch.epg_channel_id.takeIf { it.isNotBlank() },
                        isFavorite = ch.stream_id in favoriteIds
                    )
                }
                Result.success(channels to searched.size)
            }
            else -> api.getChannels(baseUrl, genreId, page, search).map { response ->
                val data = response.js ?: return@map emptyList<Channel>() to 0
                val channels: List<Channel> = data.data.map { it.toDomain(favoriteIds) }
                channels to data.total_items
            }
        }
    }

    /**
     * Resolves a channel's playable URL.
     *  - STALKER: calls create_link.
     *  - M3U / XTREAM: the channel's [Channel.cmd] already holds the final URL —
     *    returned as-is.
     */
    suspend fun createLink(baseUrl: String, cmd: String): Result<String> {
        val type = authManager.currentPortal?.type ?: PortalType.STALKER
        return when (type) {
            PortalType.M3U, PortalType.XTREAM -> Result.success(cmd)
            else -> api.createLink(baseUrl, cmd).map { response ->
                val link = response.js?.cmd ?: throw Exception("No stream URL returned")
                extractUrl(link)
            }
        }
    }

    private fun extractUrl(cmd: String): String {
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

    private fun XtreamCodesApi.CategoryInfo.toDomain() = ChannelCategory(
        id = category_id,
        title = category_name,
        alias = category_name
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

    companion object {
        private const val PAGE_SIZE = 50
    }
}
