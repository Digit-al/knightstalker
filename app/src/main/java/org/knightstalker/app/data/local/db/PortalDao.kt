// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.local.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.knightstalker.app.data.local.entity.PortalEntity

@Dao
interface PortalDao {
    @Query("SELECT * FROM portals ORDER BY lastConnected DESC")
    fun getAllPortals(): Flow<List<PortalEntity>>

    @Query("SELECT * FROM portals WHERE id = :id")
    suspend fun getPortalById(id: Long): PortalEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPortal(portal: PortalEntity): Long

    @Update
    suspend fun updatePortal(portal: PortalEntity)

    @Delete
    suspend fun deletePortal(portal: PortalEntity)

    @Query("UPDATE portals SET lastConnected = :time WHERE id = :id")
    suspend fun updateLastConnected(id: Long, time: Long)
}
