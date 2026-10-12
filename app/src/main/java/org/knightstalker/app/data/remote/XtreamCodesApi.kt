// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Client for Xtream Codes compatible panels.
 *
 * Supports both the `action=` variant (Xtream UI / TiviX) and the `section=` variant
 * (original Xtream Codes).  The `action=` variant is tried first because it works
 * on a broader set of providers (including VPN/proxy panels that only expose
 * that API surface).
 *
 * Base URL is of the form `http://host:port` or `http://host:port/panel_api`.
 */
class XtreamCodesApi(private val client: OkHttpClient, private val json: Json) {

    // ------------------------------------------------------------------ DTOs

    @Serializable
    data class XtreamResponse(
        val user_info: UserInfo? = null,
        val server_info: ServerInfo? = null,
        val stream_list: List<StreamInfo>? = null,
        val categories: List<CategoryInfo>? = null,
        val on_demand_list: List<VodItem>? = null,
        val series_list: List<SeriesItem>? = null
    )

    @Serializable
    data class UserInfo(
        val username: String = "",
        val password: String = "",
        val status: String = "",
        val exp_date: String = "",
        val max_connections: String = ""
    )

    @Serializable
    data class ServerInfo(
        val url: String = "",
        val port: String = "",
        val server_time: String = "",
        val time_zone: String = ""
    )

    @Serializable
    data class StreamInfo(
        val num: Int = 0,
        val name: String = "",
        val stream_type: String = "",
        val stream_id: String = "",
        val epg_channel_id: String = "",
        val stream_icon: String = "",
        val category_id: String = "",
        val direct_source: String = ""
    )

    @Serializable
    data class CategoryInfo(
        val category_id: String = "",
        val category_name: String = "",
        val parent_id: Int = 0
    )

    @Serializable
    data class VodItem(
        val num: Int = 0,
        val name: String = "",
        val stream_type: String = "",
        val stream_id: String = "",
        val container_extension: String = "",
        val rating: String = "",
        val rating_5based: String = "",
        val plot: String = "",
        val plot_en: String = "",
        val cast: String = "",
        val releasedate: String = "",
        val releaseDate: String = "",
        val movie_image: String = "",
        val cover: String = "",
        val backdrop_path: List<String?>? = null,
        val category_id: String = "",
        val category_ids: List<String>? = null
    ) {
        val extension: String get() = container_extension.ifBlank { "mp4" }
        val image: String get() = cover.ifBlank { movie_image }
            .ifBlank { backdrop_path?.firstOrNull { it != null } ?: "" }
    }

    @Serializable
    data class SeriesItem(
        val num: Int = 0,
        val name: String = "",
        val series_id: String = "",
        val cover: String = "",
        val cover_big: String = "",
        val movie_image: String = "",
        val plot: String = "",
        val cast: String = "",
        val director: String = "",
        val genre: String = "",
        val releasedate: String = "",
        val releaseDate: String = "",
        val rating: String = "",
        val rating_5based: String = "",
        val backdrop_path: List<String?>? = null,
        val category_id: String = ""
    ) {
        val image: String get() = cover.ifBlank { cover_big }
            .ifBlank { movie_image }
            .ifBlank { backdrop_path?.firstOrNull { it != null } ?: "" }
    }

    @Serializable
    data class Episode(
        val id: String = "",
        val episode_num: String = "",
        val title: String = "",
        val container_extension: String = "",
        val info: InfoBlock? = null
    ) {
        val extension: String get() = container_extension.ifBlank { "mp4" }

        @Serializable
        data class InfoBlock(
            val id: String = "",
            val season: String = "",
            val title: String = "",
            val container_extension: String = "",
            val plot: String = ""
        )
    }

    // ------------------------------------------------------------- helpers

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /**
     * Builds the base player_api URL.  The `action=` variant is preferred;
     * `section=` is used as a fallback for panels that only support the
     * original Xtream Codes protocol.
     */
    private fun api(
        baseUrl: String,
        username: String,
        password: String,
        action: String? = null,
        section: String? = null,
        extra: Map<String, String> = emptyMap()
    ): String {
        val base = baseUrl.trimEnd('/')
            .substringBefore("/panel_api")
            .substringBefore("/player_api")
            .substringBefore("/get.php")
            .trimEnd('/')
        val sb = StringBuilder(base)
            .append("/player_api.php?username=")
            .append(enc(username))
            .append("&password=")
            .append(enc(password))
        if (action != null) sb.append("&action=").append(action)
        if (section != null) sb.append("&section=").append(section)
        for ((k, v) in extra) sb.append("&").append(k).append("=").append(enc(v))
        return sb.toString()
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }
            response.body?.string() ?: ""
        }
    }

    private suspend fun getJson(url: String): kotlinx.serialization.json.JsonElement =
        kotlinx.serialization.json.Json.parseToJsonElement(get(url))

    // -------------------------------------------------------------- public

    /**
     * Verifies credentials by requesting the live stream list.
     * Tries the `action=` variant first, falls back to `section=`.
     */
    suspend fun getLiveStreams(
        baseUrl: String,
        username: String,
        password: String
    ): Result<List<StreamInfo>> {
        return try {
            // Try action variant first (broader compatibility)
            val url = api(baseUrl, username, password, action = "get_live_streams")
            val body = get(url)
            val element = kotlinx.serialization.json.Json.parseToJsonElement(body)
            if (element is kotlinx.serialization.json.JsonArray) {
                val list = element.map { json.decodeFromJsonElement(StreamInfo.serializer(), it) }
                Result.success(list)
            } else {
                // Fallback: section=live
                val url2 = api(baseUrl, username, password, section = "live")
                val body2 = get(url2)
                val resp = json.decodeFromString(XtreamResponse.serializer(), body2)
                Result.success(resp.stream_list ?: emptyList())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getCategories(
        baseUrl: String,
        username: String,
        password: String,
        type: String = "live"
    ): Result<List<CategoryInfo>> {
        val action = when (type) {
            "live" -> "get_live_categories"
            "movie" -> "get_vod_categories"
            "series" -> "get_series_categories"
            else -> "get_live_categories"
        }
        return try {
            // Try action variant first
            val url = api(baseUrl, username, password, action = action)
            val body = get(url)
            val element = kotlinx.serialization.json.Json.parseToJsonElement(body)
            if (element is kotlinx.serialization.json.JsonArray) {
                val list = element.map { json.decodeFromJsonElement(CategoryInfo.serializer(), it) }
                Result.success(list)
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getLiveCategory(
        baseUrl: String,
        username: String,
        password: String,
        categoryId: String
    ): Result<List<StreamInfo>> {
        return try {
            val url = api(
                baseUrl, username, password,
                action = "get_live_streams",
                extra = mapOf("category_id" to categoryId)
            )
            val body = get(url)
            val element = kotlinx.serialization.json.Json.parseToJsonElement(body)
            if (element is kotlinx.serialization.json.JsonArray) {
                val list = element.map { json.decodeFromJsonElement(StreamInfo.serializer(), it) }
                Result.success(list)
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getVodStreams(
        baseUrl: String,
        username: String,
        password: String,
        categoryId: String? = null
    ): Result<List<VodItem>> {
        return try {
            val extra = if (categoryId.isNullOrBlank()) emptyMap() else mapOf("category_id" to categoryId)
            val url = api(baseUrl, username, password, action = "get_vod_streams", extra = extra)
            val body = get(url)
            val element = kotlinx.serialization.json.Json.parseToJsonElement(body)
            val list = when (element) {
                is kotlinx.serialization.json.JsonArray ->
                    element.map { json.decodeFromJsonElement(VodItem.serializer(), it) }
                else -> {
                    // Fallback: section=on_demand
                    val url2 = api(baseUrl, username, password, section = "on_demand")
                    val resp = json.decodeFromString(XtreamResponse.serializer(), get(url2))
                    resp.on_demand_list ?: emptyList()
                }
            }
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Series detail: seasons metadata + episodes (when the server provides them).
     * The server may return `episodes` (a map keyed by season number, the standard
     * format) and/or `seasons` (a metadata list, the newer format). Both are
     * captured; callers should prefer `episodes` when present.
     */
    suspend fun getSeriesInfo(
        baseUrl: String,
        username: String,
        password: String,
        seriesId: String
    ): Result<JsonElement> {
        return try {
            val url = api(baseUrl, username, password, action = "get_series_info", extra = mapOf("series_id" to seriesId))
            val body = get(url)
            Result.success(kotlinx.serialization.json.Json.parseToJsonElement(body))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSeriesList(
        baseUrl: String,
        username: String,
        password: String,
        categoryId: String? = null
    ): Result<List<SeriesItem>> {
        return try {
            val extra = if (categoryId.isNullOrBlank()) emptyMap() else mapOf("category_id" to categoryId)
            val url = api(baseUrl, username, password, action = "get_series", extra = extra)
            val body = get(url)
            val element = kotlinx.serialization.json.Json.parseToJsonElement(body)
            val list = when (element) {
                is kotlinx.serialization.json.JsonArray ->
                    element.map { json.decodeFromJsonElement(SeriesItem.serializer(), it) }
                else -> {
                    val url2 = api(baseUrl, username, password, section = "series")
                    val resp = json.decodeFromString(XtreamResponse.serializer(), get(url2))
                    resp.series_list ?: emptyList()
                }
            }
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // -------------------------------------------------------------- URLs

    /**
     * Builds the playable stream URL for a live channel:
     * `http://host:port/live/user/pass/streamId.m3u8`
     */
    fun buildLiveStreamUrl(
        baseUrl: String,
        username: String,
        password: String,
        streamId: String,
        ext: String = "m3u8"
    ): String {
        val base = baseUrl.trimEnd('/')
            .substringBefore("/panel_api")
            .substringBefore("/player_api")
            .substringBefore("/get.php")
            .trimEnd('/')
        return "$base/live/${enc(username)}/${enc(password)}/${streamId}.$ext"
    }

    /**
     * Builds the playable VOD URL:
     * `http://host:port/movie/user/pass/streamId/name.mp4`
     */
    fun buildVodUrl(
        baseUrl: String,
        username: String,
        password: String,
        streamId: String,
        ext: String = "mp4"
    ): String {
        val base = baseUrl.trimEnd('/')
            .substringBefore("/panel_api")
            .substringBefore("/player_api")
            .substringBefore("/get.php")
            .trimEnd('/')
        return "$base/movie/${enc(username)}/${enc(password)}/$streamId.$ext"
    }

    /**
     * Builds the playable series episode URL:
     * `http://host:port/series/user/pass/episodeId/name.mp4`
     */
    fun buildSeriesUrl(
        baseUrl: String,
        username: String,
        password: String,
        episodeId: String,
        ext: String = "mp4"
    ): String {
        val base = baseUrl.trimEnd('/')
            .substringBefore("/panel_api")
            .substringBefore("/player_api")
            .substringBefore("/get.php")
            .trimEnd('/')
        return "$base/series/${enc(username)}/${enc(password)}/$episodeId.$ext"
    }

    /**
     * Resolves the final playable URL for a live stream.
     */
    suspend fun resolveStreamUrl(
        baseUrl: String,
        username: String,
        password: String,
        cmd: String
    ): Result<String> {
        return Result.success(buildLiveStreamUrl(baseUrl, username, password, cmd))
    }
}
