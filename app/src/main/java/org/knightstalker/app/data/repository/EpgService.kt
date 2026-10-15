// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import org.knightstalker.app.data.local.db.PortalDao
import org.knightstalker.app.data.local.entity.PortalEntity
import org.knightstalker.app.data.remote.parser.XmltvParser
import org.knightstalker.app.domain.model.EpgEntry
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * XMLTV EPG service.
 *
 * Fetches and caches the portal's XMLTV feed (from the portal's `epgUrl`), parses it
 * once into per-channel schedules, then answers per-channel EPG queries locally.
 * This is the EPG source for M3U and Xtream portals — Stalker portals keep using
 * the Stalker EPG API.
 *
 * Channel matching: a channel's EPG key (`tvg-id` for M3U, `epg_channel_id` for
 * Xtream) is matched against the feed's `<channel id="...">` values — exactly,
 * then case-insensitively, then case-insensitively without extension/whitespace
 * (e.g. `france2.france.tv` vs `france2francetv`).
 */
class EpgService(
    private val client: OkHttpClient,
    private val portalDao: PortalDao,
    private val parser: XmltvParser = XmltvParser()
) {

    /** Cache entry: parsed feed + the set of channel ids it covers. */
    private data class FeedCache(
        val fetchedAt: Long,
        val schedules: Map<String, List<EpgEntry>>,
        val channelIds: Set<String>
    )

    private val cache = mutableMapOf<Long, FeedCache>()

    private val _status = MutableStateFlow(EpgStatus())
    /** UI-facing status of the active portal's XMLTV feed. */
    val status: StateFlow<EpgStatus> = _status

    data class EpgStatus(
        val isLoading: Boolean = false,
        val channelCount: Int = 0,
        val entryCount: Int = 0,
        val lastFetched: Long = 0,
        val error: String? = null,
        val isUpToDate: Boolean = true
    )

    companion object {
        private const val TAG = "EpgService"
        private const val STALE_MS = 24 * 60 * 60_000L // 24 h

        fun isStale(fetchedAt: Long): Boolean =
            System.currentTimeMillis() - fetchedAt > STALE_MS
    }

    /**
     * Ensures the XMLTV feed for [portalId] is cached and fresh (24 h TTL).
     * Returns true when the feed is available and fresh after this call.
     */
    suspend fun ensureFeed(portalId: Long, force: Boolean = false): Boolean {
        val entity = portalDao.getPortalById(portalId) ?: return false
        val url = entity.epgUrl.takeIf { it.isNotBlank() } ?: return false

        synchronized(cache) {
            val existing = cache[portalId]
            if (existing != null && !force && !isStale(existing.fetchedAt)) {
                return true
            }
        }

        _status.update { it.copy(isLoading = true, error = null) }
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw Exception("HTTP ${response.code}: ${response.message}")
                }
                // OkHttp already transparently decodes Content-Encoding: gzip.
                // Additionally handle gzip *content* (.xml.gz feeds): detect the
                // gzip magic bytes (1F 8B) and inflate — many EPG providers serve
                // compressed XMLTV regardless of the response headers.
                val raw = response.body?.bytes() ?: throw Exception("Empty EPG feed")
                val xml = if (raw.size >= 2 && (raw[0].toInt() and 0xFF) == 0x1F && (raw[1].toInt() and 0xFF) == 0x8B) {
                    GZIPInputStream(ByteArrayInputStream(raw)).bufferedReader().use { it.readText() }
                } else {
                    String(raw)
                }
                if (xml.isBlank()) throw Exception("Empty EPG feed")

                val schedules = parser.parse(xml, day = null)
                val channels = parser.parseChannels(xml)
                val channelIds = channels.map { it.id }.toSet()

                // Entries may reference channels that were never declared — keep them.
                val allIds = (channelIds + schedules.keys).toSet()
                val entryCount = schedules.values.sumOf { it.size }

                synchronized(cache) {
                    cache[portalId] = FeedCache(
                        fetchedAt = System.currentTimeMillis(),
                        schedules = schedules,
                        channelIds = allIds
                    )
                }
                _status.update {
                    it.copy(
                        isLoading = false,
                        channelCount = allIds.size,
                        entryCount = entryCount,
                        lastFetched = System.currentTimeMillis(),
                        error = null,
                        isUpToDate = true
                    )
                }
                Log.d(TAG, "XMLTV feed for portal $portalId: $allIds channels, $entryCount entries")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load XMLTV feed", e)
                _status.update { it.copy(isLoading = false, error = e.message) }
                false
            }
        }
    }

    /**
     * Returns the schedule for [epgKey] on [day] ("yyyy-MM-dd") from the cached feed
     * of [portalId], or an empty list when the feed has no matching channel.
     */
    fun entriesFor(portalId: Long, epgKey: String?, day: String?): List<EpgEntry> {
        if (epgKey.isNullOrBlank()) return emptyList()
        val feed = cache[portalId] ?: return emptyList()
        val resolved = resolveChannelId(feed, epgKey) ?: return emptyList()
        val entries = feed.schedules[resolved] ?: return emptyList()
        if (day == null) return entries
        val dayStart = dayStartEpoch(day) ?: return entries
        val dayEnd = dayStart + 86_400
        return entries.filter { it.endTime > dayStart && it.startTime < dayEnd }
    }

    /**
     * Returns the short now/next window for [epgKey] (up to [size] entries around "now").
     */
    fun shortEntries(portalId: Long, epgKey: String?, size: Int = 10): List<EpgEntry> {
        if (epgKey.isNullOrBlank()) return emptyList()
        val feed = cache[portalId] ?: return emptyList()
        val resolved = resolveChannelId(feed, epgKey) ?: return emptyList()
        val entries = feed.schedules[resolved] ?: return emptyList()
        val now = System.currentTimeMillis() / 1000
        val nowPlaying = entries.find { it.startTime <= now && it.endTime > now }
        val upcoming = entries.filter { it.startTime > now }.sortedBy { it.startTime }
        val result = mutableListOf<EpgEntry>()
        nowPlaying?.let { result.add(it.copy(progressPercent = progressOf(it, now))) }
        result += upcoming.take(size - result.size)
        return result
    }

    private fun progressOf(entry: EpgEntry, now: Long): Float {
        val span = entry.endTime - entry.startTime
        if (span <= 0) return 0f
        return ((now - entry.startTime).toFloat() / span.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * Resolves an arbitrary EPG key to a feed channel id.
     */
    private fun resolveChannelId(feed: FeedCache, epgKey: String): String? {
        if (epgKey in feed.channelIds) return epgKey
        val lower = epgKey.lowercase()
        feed.channelIds.firstOrNull { it.lowercase() == lower }?.let { return it }
        val compact = lower.replace(Regex("[^a-z0-9]"), "")
        feed.channelIds.firstOrNull { it.lowercase().replace(Regex("[^a-z0-9]"), "") == compact }?.let { return it }
        return null
    }

    /** Drops the cache for a portal (e.g. when its EPG URL changes or it is deleted). */
    fun clearCache(portalId: Long) {
        synchronized(cache) { cache.remove(portalId) }
    }

    private fun dayStartEpoch(day: String): Long? {
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            fmt.parse(day)?.time?.div(1000)
        } catch (_: Exception) {
            null
        }
    }

    fun destroy() {
        synchronized(cache) { cache.clear() }
    }
}
