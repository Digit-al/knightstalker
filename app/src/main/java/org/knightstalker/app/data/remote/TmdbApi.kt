// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Minimal client for the TMDB v3 REST API (https://www.themoviedb.org/documentation/api).
 *
 * Used only to *enrich* VOD detail screens with metadata the Xtream portal does not
 * expose on its `action=` endpoint (which returns only `name`, `stream_icon`, `rating`,
 * `tmdb`, `trailer`). Xtream does supply a TMDB id (`tmdb`), so when a key is configured
 * we can look up synopsis, genres, cast, runtime and the exact release date.
 *
 * The whole feature is optional: when no API key is set, [TmdbApi] reports "disabled"
 * and callers simply fall back to the (sparse) portal fields.
 */
class TmdbApi(private val client: OkHttpClient, private val json: Json, private val apiKey: String) {

    private val base = "https://api.themoviedb.org/3"

    /** True when a TMDB key is present, i.e. enrichment is possible. */
    fun isEnabled(): Boolean = apiKey.isNotBlank()

    /** Fetch a movie's details (credits appended) in the given ISO language. */
    suspend fun movieDetails(movieId: String, language: String = "fr-FR"): Result<TmdbMovie> {
        if (!isEnabled()) return Result.failure(IllegalStateException("TMDB disabled"))
        val url = "$base/movie/$movieId" +
            "?api_key=" + enc(apiKey) +
            "&language=" + enc(language) +
            "&append_to_response=credits"
        val request = Request.Builder().url(url).get().build()
        return try {
            val body = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { resp ->
                    if (resp.code == 404) return@withContext ""
                    if (!resp.isSuccessful) {
                        throw Exception("HTTP ${resp.code}: ${resp.message}")
                    }
                    resp.body?.string() ?: ""
                }
            }
            if (body.isBlank()) {
                Result.failure(Exception("TMDB: no such movie"))
            } else {
                val m = json.decodeFromString(TmdbMovie.serializer(), body)
                Result.success(m)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    // ------------------------------------------------------------------ DTOs

    @Serializable
    data class TmdbMovie(
        val id: Int = 0,
        val title: String = "",
        val original_title: String = "",
        val overview: String = "",
        val tagline: String = "",
        val runtime: Int? = null,
        val vote_average: Double? = null,
        val status: String = "",
        val release_date: String = "",
        val genres: List<TmdbGenre> = emptyList(),
        val credits: TmdbCredits? = null
    ) {
        /** Director — found in `crew` (the only list carrying a `job` field). */
        fun director(): String? {
            val d = credits?.crew?.firstOrNull { it.job.equals("Director", ignoreCase = true) }?.name
            return d?.ifBlank { null }
        }

        /** Top billed cast (in order), as a comma-separated line. */
        fun castLine(limit: Int = 8): String {
            val names = credits?.cast
                ?.filter { it.name.isNotBlank() }
                ?.take(limit)
                ?.map { it.name }
                .orEmpty()
            return names.joinToString(", ")
        }

        fun genreNames(): List<String> = genres.map { it.name }

        /** "2011" from a "2011-10-14" release date, else blank. */
        fun releaseYear(): String = release_date.take(4).ifBlank { "" }
    }

    @Serializable
    data class TmdbGenre(val id: Int = 0, val name: String = "")

    @Serializable
    data class TmdbCredits(
        val cast: List<TmdbPerson> = emptyList(),
        val crew: List<TmdbPerson> = emptyList()
    )

    @Serializable
    data class TmdbPerson(
        val name: String = "",
        val job: String = ""
    )
}
