// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Client for Xtream Codes compatible panels (Xtream, Stalker-to-Xtream bridges, etc.).
 *
 * Base URL is of the form `http://host:port` or `http://host:port/panel_api`. The API
 * endpoints live at `/player_api.php` (live) and `/get.php` (stream URLs).
 */
class XtreamCodesApi(private val client: OkHttpClient, private val json: Json) {

    // ------------------------------------------------------------------ DTOs

    @Serializable
    data class XtreamResponse(
        val user_info: UserInfo? = null,
        val server_info: ServerInfo? = null,
        val stream_list: List<StreamInfo>? = null,
        val categories: List<CategoryInfo>? = null
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
        val stream_icon: String = ""
    )

    @Serializable
    data class CategoryInfo(
        val category_id: String = "",
        val category_name: String = ""
    )

    // ------------------------------------------------------------- helpers

    private fun baseOf(baseUrl: String): String {
        // Strip panel_api / player_api / get.php if the user pasted a full endpoint URL.
        return baseUrl.trimEnd('/').substringBefore("/panel_api")
            .substringBefore("/player_api")
            .substringBefore("/get.php")
            .trimEnd('/')
    }

    private suspend fun get(url: String, deserializer: kotlinx.serialization.DeserializationStrategy<XtreamResponse>): Result<XtreamResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                }
                val body = response.body?.string() ?: ""
                Result.success(json.decodeFromString(deserializer, body))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    // -------------------------------------------------------------- public

    /**
     * Verifies the credentials by requesting the live stream list.
     * Returns the full live stream list on success.
     */
    suspend fun getLiveStreams(baseUrl: String, username: String, password: String): Result<List<StreamInfo>> {
        val url = "$baseUrl/player_api.php?username=$username&password=$password&section=live"
        return get(url, XtreamResponse.serializer()).map { it.stream_list ?: emptyList() }
    }

    suspend fun getCategories(baseUrl: String, username: String, password: String): Result<List<CategoryInfo>> {
        val url = "$baseUrl/player_api.php?username=$username&password=$password&section=categories&type=live"
        return get(url, XtreamResponse.serializer()).map { it.categories ?: emptyList() }
    }

    /**
     * Live streams of one category. Xtream panels can return very large lists here —
     * this is the main payload for the Live TV grid.
     */
    suspend fun getLiveCategory(
        baseUrl: String,
        username: String,
        password: String,
        categoryId: String
    ): Result<List<StreamInfo>> {
        val url = "$baseUrl/player_api.php?username=$username&password=$password&category_id=$categoryId&section=live"
        return get(url, XtreamResponse.serializer()).map { it.stream_list ?: emptyList() }
    }

    /**
     * Builds the playable stream URL for a live channel:
     * `http://host:port/get.php?token=...&channel=123`
     */
    fun buildLiveStreamUrl(baseUrl: String, username: String, password: String, streamId: String): String {
        val base = baseOf(baseUrl)
        return "$base/get.php?username=$username&password=$password&channel=$streamId"
    }

    /**
     * Resolves the final playable URL for a live stream. On Xtream panels the
     * get.php URL is directly playable, so it is returned as-is; the helper exists
     * so the call-site stays uniform with other source types.
     */
    suspend fun resolveStreamUrl(baseUrl: String, username: String, password: String, cmd: String): Result<String> {
        return Result.success(buildLiveStreamUrl(baseUrl, username, password, cmd))
    }
}
