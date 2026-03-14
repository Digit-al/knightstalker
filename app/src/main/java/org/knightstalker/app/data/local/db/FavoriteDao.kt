// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.local.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.knightstalker.app.data.local.entity.FavoriteEntity

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites WHERE portalId = :portalId ORDER BY addedAt DESC")
    fun getFavorites(portalId: Long): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE portalId = :portalId AND type = :type ORDER BY addedAt DESC")
    fun getFavoritesByType(portalId: Long, type: String): Flow<List<FavoriteEntity>>

    @Query("SELECT channelId FROM favorites WHERE portalId = :portalId")
    suspend fun getFavoriteIds(portalId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE channelId = :channelId AND portalId = :portalId")
    suspend fun removeFavorite(channelId: String, portalId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE channelId = :channelId AND portalId = :portalId)")
    suspend fun isFavorite(channelId: String, portalId: Long): Boolean
}
