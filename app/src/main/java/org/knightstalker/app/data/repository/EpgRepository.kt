// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.repository

import org.knightstalker.app.data.remote.StalkerApi
import org.knightstalker.app.data.remote.dto.EpgData
import org.knightstalker.app.data.remote.dto.ShortEpgEntry
import org.knightstalker.app.domain.model.EpgEntry

class EpgRepository(private val api: StalkerApi) {

    suspend fun getShortEpg(baseUrl: String, channelId: String): Result<List<EpgEntry>> {
        return api.getShortEpg(baseUrl, channelId).map { response ->
            val data = response.js?.entries ?: emptyList()
            data.map { it.toDomain(channelId) }
        }
    }

    suspend fun getDayEpg(baseUrl: String, channelId: String, date: String): Result<List<EpgEntry>> {
        return api.getEpg(baseUrl, channelId, date).map { response ->
            val data = response.js?.data ?: emptyList()
            data.map { it.toDomain() }
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
