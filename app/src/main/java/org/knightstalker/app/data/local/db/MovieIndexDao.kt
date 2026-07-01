// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.local.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.knightstalker.app.data.local.entity.MovieIndexEntity

@Dao
interface MovieIndexDao {

    @Query("""
        SELECT * FROM movie_index
        WHERE portalId = :portalId
          AND (:categoryId IS NULL OR categoryId = :categoryId)
          AND (:excludeAdult = 0 OR isAdult = 0)
          AND (:ratedOnly = 0 OR rating IS NOT NULL)
          AND (:minYear IS NULL OR year >= :minYear)
          AND (:hasQuery = 0 OR rowid IN (
                SELECT rowid FROM movie_fts WHERE movie_fts MATCH :ftsQuery
              ))
        ORDER BY
          CASE WHEN :sort = 'RATING' THEN rating END DESC,
          CASE WHEN :sort = 'YEAR'   THEN year   END DESC,
          CASE WHEN :sort = 'ADDED'  THEN addedAt END DESC,
          sortName ASC
    """)
    fun pageMovies(
        portalId: Long,
        categoryId: String?,
        excludeAdult: Int,
        ratedOnly: Int,
        minYear: Int?,
        hasQuery: Int,
        ftsQuery: String,
        sort: String
    ): PagingSource<Int, MovieIndexEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<MovieIndexEntity>)

    @Query("SELECT COUNT(*) FROM movie_index WHERE portalId = :portalId")
    fun observeCount(portalId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM movie_index WHERE portalId = :portalId")
    suspend fun count(portalId: Long): Int

    @Query("SELECT itemId FROM movie_index WHERE portalId = :portalId AND itemId IN (:ids)")
    suspend fun existingIds(portalId: Long, ids: List<String>): List<String>

    @Query("DELETE FROM movie_index WHERE portalId = :portalId AND syncEpoch < :syncEpoch")
    suspend fun prune(portalId: Long, syncEpoch: Long)

    @Query("DELETE FROM movie_index WHERE portalId = :portalId")
    suspend fun clear(portalId: Long)

    @Query("SELECT DISTINCT categoryId FROM movie_index WHERE portalId = :portalId")
    suspend fun distinctCategories(portalId: Long): List<String>
}
