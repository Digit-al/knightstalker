// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.repository

import androidx.paging.PagingSource
import kotlinx.coroutines.flow.Flow
import org.knightstalker.app.data.local.db.MovieIndexDao
import org.knightstalker.app.data.local.db.SeriesIndexDao
import org.knightstalker.app.data.local.db.SyncStateDao
import org.knightstalker.app.data.local.entity.MovieIndexEntity
import org.knightstalker.app.data.local.entity.SeriesIndexEntity

class MediaIndexRepository(
    private val movieDao: MovieIndexDao,
    private val seriesDao: SeriesIndexDao,
    private val syncStateDao: SyncStateDao
) {
    fun pageMovies(
        portalId: Long, categoryId: String?, excludeAdult: Int, ratedOnly: Int,
        minYear: Int?, hasQuery: Int, ftsQuery: String, sort: String
    ): PagingSource<Int, MovieIndexEntity> =
        movieDao.pageMovies(portalId, categoryId, excludeAdult, ratedOnly, minYear, hasQuery, ftsQuery, sort)

    fun pageSeries(
        portalId: Long, categoryId: String?, excludeAdult: Int, ratedOnly: Int,
        minYear: Int?, hasQuery: Int, ftsQuery: String, sort: String
    ): PagingSource<Int, SeriesIndexEntity> =
        seriesDao.pageSeries(portalId, categoryId, excludeAdult, ratedOnly, minYear, hasQuery, ftsQuery, sort)

    fun observeMovieCount(portalId: Long): Flow<Int> = movieDao.observeCount(portalId)
    fun observeSeriesCount(portalId: Long): Flow<Int> = seriesDao.observeCount(portalId)

    suspend fun clearPortal(portalId: Long) { movieDao.clear(portalId); seriesDao.clear(portalId) }
}
