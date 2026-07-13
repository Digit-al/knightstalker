// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.repository

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.knightstalker.app.data.local.db.SeriesIndexDao
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Fills series ratings from TVmaze (https://www.tvmaze.com) — a free, key-less,
 * CC BY-SA-licensed TV database. The portal's own series "ratings" are unreliable
 * (usually a bogus 10.0), so for series we look the show up on TVmaze by cleaned
 * name and cache the real rating into the local index.
 *
 * Lazy + cached: fetched only for series the user actually sees, once per title
 * (guarded by [SeriesIndexDao.ratingCheckedAt]), and throttled to stay within
 * TVmaze's ~20 requests / 10 s / IP budget.
 */
class SeriesRatingRepository(
    private val client: OkHttpClient,
    private val json: Json,
    private val seriesDao: SeriesIndexDao
) {
    private val handled = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val rateMutex = Mutex()
    private var nextAllowedAt = 0L

    /** Look up and cache the TVmaze rating for one series, unless it's already been checked. */
    suspend fun ensureRating(portalId: Long, itemId: String, rawName: String, year: Int?) {
        val key = "$portalId:$itemId"
        if (!handled.add(key)) return                       // already handled this session
        try {
            if ((seriesDao.ratingCheckedAt(portalId, itemId) ?: 0L) != 0L) return  // checked before
            val query = cleanTitle(rawName, year)
            if (query.isBlank()) {
                seriesDao.updateRating(portalId, itemId, null, System.currentTimeMillis())
                return
            }
            when (val result = withContext(Dispatchers.IO) { fetchRating(query) }) {
                is Fetch.Ok ->
                    seriesDao.updateRating(portalId, itemId, result.value, System.currentTimeMillis())
                // No match / no rating on TVmaze: mark checked so we don't keep retrying.
                Fetch.NoData ->
                    seriesDao.updateRating(portalId, itemId, null, System.currentTimeMillis())
                // Transient error (network / 429 / 5xx): leave unchecked to retry later.
                Fetch.Retry -> handled.remove(key)
            }
        } catch (e: Exception) {
            handled.remove(key)                             // allow a retry next time
            Log.d(TAG, "ensureRating failed for '$rawName': ${e.message}")
        }
    }

    private sealed interface Fetch {
        data class Ok(val value: Double) : Fetch
        object NoData : Fetch
        object Retry : Fetch
    }

    private suspend fun fetchRating(query: String): Fetch {
        throttle()
        val url = "https://api.tvmaze.com/singlesearch/shows?q=" +
            URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "KnightStalker/1.1 (+https://gitlab.com/hc51t/knightstalker)")
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                when {
                    resp.code == 404 -> Fetch.NoData                 // no show matched
                    !resp.isSuccessful -> Fetch.Retry                // 429 / 5xx -> retry later
                    else -> {
                        val body = resp.body?.string().orEmpty()
                        if (body.isBlank()) return Fetch.NoData
                        val show = json.decodeFromString(TvMazeShow.serializer(), body)
                        val avg = show.rating?.average
                        if (avg != null && avg > 0.0) Fetch.Ok(avg.coerceIn(0.0, 10.0)) else Fetch.NoData
                    }
                }
            }
        } catch (e: Exception) {
            Fetch.Retry
        }
    }

    /** Space requests out to stay under TVmaze's rate limit, across concurrent callers. */
    private suspend fun throttle() {
        val waitFor = rateMutex.withLock {
            val now = SystemClock.elapsedRealtime()
            val wait = (nextAllowedAt - now).coerceAtLeast(0L)
            nextAllowedAt = maxOf(now, nextAllowedAt) + MIN_INTERVAL_MS
            wait
        }
        if (waitFor > 0) delay(waitFor)
    }

    @Serializable
    private data class TvMazeShow(
        val name: String? = null,
        val rating: TvMazeRating? = null
    )

    @Serializable
    private data class TvMazeRating(
        val average: Double? = null
    )

    companion object {
        private const val TAG = "SeriesRating"
        // ~16 requests / 10 s, comfortably under TVmaze's 20 / 10 s / IP limit.
        private const val MIN_INTERVAL_MS = 600L

        private val LEADING_TAGS = Regex("^\\s*(\\|[^|]*\\|\\s*)+")
        private val LEADING_SEPS = Regex("^[\\s\\-–—:]+")
        private val PARENS = Regex("\\([^)]*\\)")
        private val BRACKETS = Regex("\\[[^\\]]*\\]")
        private val JUNK = Regex(
            "\\b(FHD|UHD|HD|SD|4K|VOSTFR|VOSTF|VOST|VO|MULTI|HEVC|H\\.?265|X265|H\\.?264|X264|" +
                "VFF|VFQ|VF|TS|WEB|WEBRIP|BLURAY|BDRIP|1080P|720P|480P|2160P|LA S[ÉE]RIE|THE SERIES)\\b",
            RegexOption.IGNORE_CASE
        )
        private val YEAR = Regex("\\b(19|20)\\d\\d\\b")
        private val COLLAPSE = Regex("[\\s\\-–—_]+")

        /**
         * Strip portal cruft (|FR| tags, quality markers, year, alt-title parens) down
         * to a searchable show title. e.g. "|FR| 6ixtynin9 La serie 2023 FHD (VOST)" -> "6ixtynin9".
         */
        fun cleanTitle(raw: String, year: Int?): String {
            var s = raw
            s = LEADING_TAGS.replace(s, "")
            s = LEADING_SEPS.replace(s, "")
            s = PARENS.replace(s, " ")
            s = BRACKETS.replace(s, " ")
            s = JUNK.replace(s, " ")
            if (year != null) s = s.replace(year.toString(), " ")
            s = YEAR.replace(s, " ")
            s = COLLAPSE.replace(s, " ").trim()
            return s
        }
    }
}
