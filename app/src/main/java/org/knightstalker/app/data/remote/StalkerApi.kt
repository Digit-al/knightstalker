// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.knightstalker.app.data.remote.dto.*

class StalkerApi(private val client: OkHttpClient, private val json: Json) {

    private val resolvedApiBase = mutableMapOf<String, String>()

    private fun buildUrl(baseUrl: String, type: String, action: String, params: Map<String, String> = emptyMap()): String {
        val serverPath = resolvedApiBase[baseUrl] ?: buildFallbackPath(baseUrl)
        val urlBuilder = serverPath.toHttpUrl().newBuilder()
            .addQueryParameter("type", type)
            .addQueryParameter("action", action)
            .addQueryParameter("JsHttpRequest", "1-xml")
        params.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        return urlBuilder.build().toString()
    }

    private fun buildFallbackPath(baseUrl: String): String {
        val base = baseUrl.trimEnd('/')
        return if (base.contains("/server/load.php") || base.contains("/portal.php")) base
        else "$base/server/load.php"
    }

    suspend fun resolveApiBase(baseUrl: String): String {
        resolvedApiBase[baseUrl]?.let { return it }

        val base = baseUrl.trimEnd('/')
        if (base.contains("/server/load.php") || base.contains("/portal.php")) {
            resolvedApiBase[baseUrl] = base
            return base
        }

        val parsed = base.toHttpUrl()
        val origin = "${parsed.scheme}://${parsed.host}" +
            if (parsed.port != if (parsed.scheme == "https") 443 else 80) ":${parsed.port}" else ""

        val segments = parsed.pathSegments.filter { it.isNotEmpty() }
        val candidates = mutableListOf<String>()

        // Try with full path first, then root, then portal.php variants
        if (segments.isNotEmpty()) {
            candidates.add("$origin/${segments.joinToString("/")}/server/load.php")
        }
        candidates.add("$origin/server/load.php")
        if (segments.isNotEmpty()) {
            candidates.add("$origin/${segments.joinToString("/")}/portal.php")
        }
        candidates.add("$origin/portal.php")

        val resolved = withContext(Dispatchers.IO) {
            for (candidate in candidates) {
                try {
                    val testUrl = candidate.toHttpUrl().newBuilder()
                        .addQueryParameter("type", "stb")
                        .addQueryParameter("action", "handshake")
                        .addQueryParameter("JsHttpRequest", "1-xml")
                        .build()
                    val request = Request.Builder().url(testUrl).get().build()
                    val response = client.newCall(request).execute()
                    val code = response.code
                    val body = response.body?.string() ?: ""
                    response.close()
                    Log.d("StalkerApi", "Probing $candidate -> $code, json=${body.startsWith("{")}")
                    if (code == 200 && body.startsWith("{")) {
                        return@withContext candidate
                    }
                } catch (e: Exception) {
                    Log.d("StalkerApi", "Probing $candidate -> ${e.message}")
                }
            }
            "$base/server/load.php"
        }
        Log.d("StalkerApi", "Resolved API base for $baseUrl -> $resolved")
        resolvedApiBase[baseUrl] = resolved
        return resolved
    }

    private suspend fun <T> get(url: String, deserializer: kotlinx.serialization.DeserializationStrategy<T>): Result<T> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d("StalkerApi", "URL: $url")
                val request = Request.Builder().url(url).get().build()
                val actualRequest = client.newCall(request).execute()
                val sentRequest = actualRequest.request
                Log.d("StalkerApi", "REQUEST headers=${sentRequest.headers}")
                val response = actualRequest
                Log.d("StalkerApi", "RESPONSE ${response.code} headers=${response.headers}")
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                }
                val rawBody = response.body?.string() ?: ""
                Log.d("StalkerApi", "Body length=${rawBody.length} content=[${rawBody.take(200)}]")
                val body = rawBody.takeIf { it.isNotBlank() }
                if (body == null) {
                    Log.w("StalkerApi", "Empty response for $url")
                    return@withContext Result.failure(Exception("Empty response"))
                }
                Log.d("StalkerApi", "Response: ${body.take(500)}")
                val parsed = json.decodeFromString(deserializer, body)
                Result.success(parsed)
            } catch (e: Exception) {
                Log.e("StalkerApi", "Error for $url", e)
                Result.failure(e)
            }
        }
    }

    suspend fun handshake(baseUrl: String): Result<StalkerResponse<HandshakeData>> {
        val url = buildUrl(baseUrl, "stb", "handshake")
        return get(url, StalkerResponse.serializer(HandshakeData.serializer()))
    }

    suspend fun getProfile(baseUrl: String): Result<StalkerResponse<ProfileData>> {
        val url = buildUrl(baseUrl, "stb", "get_profile")
        return get(url, StalkerResponse.serializer(ProfileData.serializer()))
    }

    suspend fun doAuth(baseUrl: String): Result<StalkerResponse<Boolean>> {
        val url = buildUrl(baseUrl, "stb", "do_auth")
        return get(url, StalkerResponse.serializer(Boolean.serializer()))
    }

    suspend fun getGenres(baseUrl: String): Result<StalkerResponse<List<GenreData>>> {
        val url = buildUrl(baseUrl, "itv", "get_genres")
        return get(url, StalkerResponse.serializer(kotlinx.serialization.builtins.ListSerializer(GenreData.serializer())))
    }

    suspend fun getChannels(baseUrl: String, genreId: String? = null, page: Int = 1, search: String? = null): Result<StalkerResponse<ChannelListData>> {
        val params = mutableMapOf("p" to page.toString(), "sortby" to "number")
        if (!genreId.isNullOrEmpty() && genreId != "*") {
            params["genre"] = genreId
        }
        if (!search.isNullOrEmpty()) {
            params["search"] = search
        }
        val url = buildUrl(baseUrl, "itv", "get_ordered_list", params)
        return get(url, StalkerResponse.serializer(ChannelListData.serializer()))
    }

    suspend fun createLink(baseUrl: String, cmd: String, type: String = "itv"): Result<StalkerResponse<CreateLinkData>> {
        val params = mapOf("cmd" to cmd, "forced_storage" to "undefined", "disable_ad" to "0")
        val url = buildUrl(baseUrl, type, "create_link", params)
        return get(url, StalkerResponse.serializer(CreateLinkData.serializer()))
    }

    suspend fun getShortEpg(baseUrl: String, channelId: String): Result<StalkerResponse<ShortEpgData>> {
        val params = mapOf("ch_id" to channelId, "size" to "10")
        val url = buildUrl(baseUrl, "itv", "get_short_epg", params)
        return get(url, StalkerResponse.serializer(
            kotlinx.serialization.builtins.ListSerializer(ShortEpgEntry.serializer())
        )).map { response ->
            StalkerResponse(js = response.js?.let { ShortEpgData(entries = it) })
        }
    }

    suspend fun getEpg(baseUrl: String, channelId: String, date: String): Result<StalkerResponse<EpgListData>> {
        val params = mapOf("ch_id" to channelId, "date" to date)
        val url = buildUrl(baseUrl, "epg", "get_simple_data_table", params)
        return get(url, StalkerResponse.serializer(EpgListData.serializer()))
    }

    suspend fun getVodCategories(baseUrl: String): Result<StalkerResponse<List<VodCategoryData>>> {
        val url = buildUrl(baseUrl, "vod", "get_categories")
        return get(url, StalkerResponse.serializer(kotlinx.serialization.builtins.ListSerializer(VodCategoryData.serializer())))
    }

    suspend fun getVodList(baseUrl: String, categoryId: String? = null, page: Int = 1, search: String? = null): Result<StalkerResponse<VodListData>> {
        val params = mutableMapOf("p" to page.toString(), "sortby" to "added")
        if (!categoryId.isNullOrEmpty() && categoryId != "*") {
            params["category"] = categoryId
        }
        if (!search.isNullOrEmpty()) {
            params["search"] = search
        }
        val url = buildUrl(baseUrl, "vod", "get_ordered_list", params)
        return get(url, StalkerResponse.serializer(VodListData.serializer()))
    }

    suspend fun createVodLink(baseUrl: String, cmd: String): Result<StalkerResponse<CreateLinkData>> {
        return createLink(baseUrl, cmd, "vod")
    }

    suspend fun getSeriesList(baseUrl: String, categoryId: String? = null, page: Int = 1, search: String? = null): Result<StalkerResponse<VodListData>> {
        val params = mutableMapOf("p" to page.toString(), "sortby" to "added")
        if (!categoryId.isNullOrEmpty() && categoryId != "*") {
            params["category"] = categoryId
        }
        if (!search.isNullOrEmpty()) {
            params["search"] = search
        }
        val url = buildUrl(baseUrl, "series", "get_ordered_list", params)
        return get(url, StalkerResponse.serializer(VodListData.serializer()))
    }

    suspend fun createSeriesLink(baseUrl: String, cmd: String): Result<StalkerResponse<CreateLinkData>> {
        return createLink(baseUrl, cmd, "series")
    }
}
