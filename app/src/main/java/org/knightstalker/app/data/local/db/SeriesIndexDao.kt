// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.local.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.knightstalker.app.data.local.entity.SeriesIndexEntity

@Dao
interface SeriesIndexDao {

    @Query("""
        SELECT * FROM series_index
        WHERE portalId = :portalId
          AND (:categoryId IS NULL OR categoryId = :categoryId)
          AND (:excludeAdult = 0 OR isAdult = 0)
          AND (:ratedOnly = 0 OR rating IS NOT NULL)
          AND (:minYear IS NULL OR year >= :minYear)
          AND (:hasQuery = 0 OR rowid IN (
                SELECT rowid FROM series_fts WHERE series_fts MATCH :ftsQuery
              ))
        ORDER BY
          CASE WHEN :sort = 'RATING' THEN rating END DESC,
          CASE WHEN :sort = 'YEAR'   THEN year   END DESC,
          CASE WHEN :sort = 'ADDED'  THEN addedAt END DESC,
          sortName ASC
    """)
    fun pageSeries(
        portalId: Long,
        categoryId: String?,
        excludeAdult: Int,
        ratedOnly: Int,
        minYear: Int?,
        hasQuery: Int,
        ftsQuery: String,
        sort: String
    ): PagingSource<Int, SeriesIndexEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<SeriesIndexEntity>)

    @Query("SELECT COUNT(*) FROM series_index WHERE portalId = :portalId")
    fun observeCount(portalId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM series_index WHERE portalId = :portalId")
    suspend fun count(portalId: Long): Int

    @Query("SELECT itemId FROM series_index WHERE portalId = :portalId AND itemId IN (:ids)")
    suspend fun existingIds(portalId: Long, ids: List<String>): List<String>

    @Query("DELETE FROM series_index WHERE portalId = :portalId AND syncEpoch < :syncEpoch")
    suspend fun prune(portalId: Long, syncEpoch: Long)

    @Query("DELETE FROM series_index WHERE portalId = :portalId")
    suspend fun clear(portalId: Long)

    @Query("SELECT DISTINCT categoryId FROM series_index WHERE portalId = :portalId")
    suspend fun distinctCategories(portalId: Long): List<String>
}
