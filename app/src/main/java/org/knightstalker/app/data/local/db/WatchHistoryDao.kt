// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.local.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.knightstalker.app.data.local.entity.WatchHistoryEntity

@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history WHERE portalId = :portalId ORDER BY lastWatched DESC")
    fun getHistory(portalId: Long): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE portalId = :portalId AND NOT isFinished ORDER BY lastWatched DESC")
    fun getContinueWatching(portalId: Long): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE itemId = :itemId AND portalId = :portalId")
    suspend fun getEntry(itemId: String, portalId: Long): WatchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WatchHistoryEntity)

    @Query("DELETE FROM watch_history WHERE itemId = :itemId AND portalId = :portalId")
    suspend fun delete(itemId: String, portalId: Long)

    @Query("DELETE FROM watch_history WHERE portalId = :portalId")
    suspend fun clearHistory(portalId: Long)

    @Query("UPDATE watch_history SET positionMs = :positionMs, durationMs = :durationMs, progressPercent = :progress, isFinished = :finished, lastWatched = :timestamp WHERE itemId = :itemId AND portalId = :portalId")
    suspend fun updatePosition(itemId: String, portalId: Long, positionMs: Long, durationMs: Long, progress: Float, finished: Boolean, timestamp: Long = System.currentTimeMillis())
}
