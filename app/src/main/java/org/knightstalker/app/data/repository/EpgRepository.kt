// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.repository

import org.knightstalker.app.data.remote.StalkerApi
import org.knightstalker.app.data.remote.StalkerAuthManager
import org.knightstalker.app.data.remote.dto.EpgData
import org.knightstalker.app.data.remote.dto.ShortEpgEntry
import org.knightstalker.app.domain.model.EpgEntry
import org.knightstalker.app.domain.model.PortalType

class EpgRepository(
    private val api: StalkerApi,
    private val authManager: StalkerAuthManager,
    private val epgService: EpgService
) {

    /**
     * Short EPG (now / next) for a channel.
     *  - STALKER: Stalker `get_short_epg`.
     *  - M3U / XTREAM: XMLTV feed via [EpgService] (empty list when the portal has
     *    no EPG configured or the channel has no matching EPG key).
     */
    suspend fun getShortEpg(baseUrl: String, channelId: String): Result<List<EpgEntry>> {
        val type = authManager.currentPortal?.type ?: PortalType.STALKER
        return when (type) {
            PortalType.M3U, PortalType.XTREAM -> {
                val portal = authManager.currentPortal ?: return Result.failure(Exception("No portal"))
                epgService.ensureFeed(portal.id)
                Result.success(epgService.shortEntries(portal.id, epgKeyFor(channelId)))
            }
            else -> api.getShortEpg(baseUrl, channelId).map { response ->
                val entries: List<ShortEpgEntry> = response.js?.entries ?: emptyList()
                entries.map { it.toDomain(channelId) }
            }
        }
    }

    /**
     * Full day EPG for a channel.
     *  - STALKER: Stalker `get_simple_data_table`.
     *  - M3U / XTREAM: XMLTV feed via [EpgService] for the requested [day].
     *
     * When [epgKey] is provided (from the already-resolved [Channel.epgKey]),
     * it is used directly — avoiding a re-lookup in `authManager.xtreamStreams`
     * which can be empty on cold start or after a reconnection.
     */
    suspend fun getDayEpg(
        baseUrl: String,
        channelId: String,
        day: String,
        epgKey: String? = null
    ): Result<List<EpgEntry>> {
        val type = authManager.currentPortal?.type ?: PortalType.STALKER
        return when (type) {
            PortalType.M3U, PortalType.XTREAM -> {
                val portal = authManager.currentPortal ?: return Result.failure(Exception("No portal"))
                epgService.ensureFeed(portal.id)
                val key = epgKey?.takeIf { it.isNotBlank() } ?: epgKeyFor(channelId)
                Result.success(epgService.entriesFor(portal.id, key, day))
            }
            else -> api.getEpg(baseUrl, channelId, day).map { response ->
                val data = response.js?.data ?: emptyList()
                data.map { it.toDomain() }
            }
        }
    }

    /**
     * Resolves the XMLTV EPG key for a channel id.
     *
     * For M3U the channel id IS the stream URL — the key is the channel's `tvg-id`.
     * For Xtream the channel id is the stream id — the key is its `epg_channel_id`.
     * The channel list is looked up from the in-memory connection data.
     */
    private fun epgKeyFor(channelId: String): String? {
        val portal = authManager.currentPortal ?: return null
        return when (portal.type) {
            PortalType.M3U -> authManager.m3uChannels
                .firstOrNull { it.id == channelId }?.tvgId
            PortalType.XTREAM -> authManager.xtreamStreams
                .firstOrNull { it.stream_id == channelId }?.epg_channel_id
            else -> null
        }
    }

    private fun ShortEpgEntry.toDomain(channelId: String): EpgEntry {
        val now = System.currentTimeMillis() / 1000
        val progress = if (now in timeUnix..timeToUnix) {
            ((now - timeUnix).toFloat() / (timeToUnix - timeUnix).toFloat()).coerceIn(0f, 1f)
        } else 0f

        return EpgEntry(
            id = "${channelId}_${timeUnix}",
            channelId = channelId,
            title = name,
            description = description.ifEmpty { null },
            startTime = timeUnix,
            endTime = timeToUnix,
            duration = durationSec,
            progressPercent = progress
        )
    }

    private fun EpgData.toDomain(): EpgEntry {
        val now = System.currentTimeMillis() / 1000
        val progress = if (now in timeUnix..timeToUnix) {
            ((now - timeUnix).toFloat() / (timeToUnix - timeUnix).toFloat()).coerceIn(0f, 1f)
        } else 0f

        return EpgEntry(
            id = id,
            channelId = chId,
            title = name,
            description = description.ifEmpty { null },
            startTime = timeUnix,
            endTime = timeToUnix,
            duration = durationSec,
            progressPercent = progress
        )
    }
}
