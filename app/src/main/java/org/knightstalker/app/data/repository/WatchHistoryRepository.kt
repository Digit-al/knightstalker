// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.repository

import kotlinx.coroutines.flow.Flow
import org.knightstalker.app.data.local.db.WatchHistoryDao
import org.knightstalker.app.data.local.entity.WatchHistoryEntity

class WatchHistoryRepository(private val dao: WatchHistoryDao) {

    fun getHistory(portalId: Long): Flow<List<WatchHistoryEntity>> {
        return dao.getHistory(portalId)
    }

    fun getContinueWatching(portalId: Long): Flow<List<WatchHistoryEntity>> {
        return dao.getContinueWatching(portalId)
    }

    suspend fun getEntry(itemId: String, portalId: Long): WatchHistoryEntity? {
        return dao.getEntry(itemId, portalId)
    }

    /** Watched episodes of a season, mapped by episode number. Keys are parsed from
     *  the "episode:<seasonCmd>:<n>" itemId scheme used by episode playback. */
    suspend fun getSeasonProgress(portalId: Long, seasonCmd: String): Map<Int, WatchHistoryEntity> {
        val prefix = "episode:$seasonCmd:"
        return dao.getByPrefix(portalId, prefix).mapNotNull { entry ->
            entry.itemId.removePrefix(prefix).toIntOrNull()?.let { ep -> ep to entry }
        }.toMap()
    }

    suspend fun savePosition(
        itemId: String,
        portalId: Long,
        name: String,
        logo: String?,
        cmd: String,
        type: String,
        positionMs: Long,
        durationMs: Long
    ) {
        val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
        // Consider finished if >95% watched
        val finished = progress > 0.95f

        val existing = dao.getEntry(itemId, portalId)
        if (existing != null) {
            dao.updatePosition(itemId, portalId, positionMs, durationMs, progress, finished)
        } else {
            dao.upsert(
                WatchHistoryEntity(
                    itemId = itemId,
                    portalId = portalId,
                    name = name,
                    logo = logo,
                    cmd = cmd,
                    type = type,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    progressPercent = progress,
                    isFinished = finished
                )
            )
        }
    }

    suspend fun deleteEntry(itemId: String, portalId: Long) {
        dao.delete(itemId, portalId)
    }

    suspend fun clearHistory(portalId: Long) {
        dao.clearHistory(portalId)
    }

    suspend fun markFinished(itemId: String, portalId: Long) {
        val entry = dao.getEntry(itemId, portalId) ?: return
        dao.upsert(entry.copy(isFinished = true, progressPercent = 1f))
    }
}
