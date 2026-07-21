// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.local.sync

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.knightstalker.app.data.local.db.MovieIndexDao
import org.knightstalker.app.data.local.db.SeriesIndexDao
import org.knightstalker.app.data.local.db.SyncStateDao
import org.knightstalker.app.data.local.entity.SyncStateEntity
import org.knightstalker.app.data.local.prefs.UserPreferences
import org.knightstalker.app.data.remote.StalkerApi
import org.knightstalker.app.data.remote.StalkerAuthManager
import org.knightstalker.app.data.remote.dto.VodData
import org.knightstalker.app.data.remote.dto.VodListData

/**
 * Owns all list-network traffic. Crawls each [MediaKind] into the local Room index and keeps
 * it fresh. The browse path never calls this directly; it only observes [status] and reads
 * Room. Decisions 4/5/6/7/8 from the implementation contract are encoded here.
 */
class SyncEngine(
    private val context: Context,
    private val api: StalkerApi,
    private val authManager: StalkerAuthManager,
    private val movieDao: MovieIndexDao,
    private val seriesDao: SeriesIndexDao,
    private val syncStateDao: SyncStateDao,
    private val preferences: UserPreferences
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Serialises mid-crawl re-handshakes so a token expiry triggers at most one in flight. */
    private val authMutex = Mutex()

    /** UI-facing status stream (status + indexed/total). */
    fun status(kind: MediaKind): Flow<SyncStateEntity?> = syncStateDao.observe(kind.key)

    // ---------------------------------------------------------------------------------------
    // State machine
    // ---------------------------------------------------------------------------------------

    /** Decide + dispatch per the §7 state machine. Safe to call on every tab open. */
    suspend fun ensureFresh(kind: MediaKind) {
        authManager.currentPortal ?: return // no active portal -> degrade gracefully
        if (!preferences.autoUpdateOnOpen.first()) return // auto-update disabled -> only manual rebuild
        val st = syncStateDao.get(kind.key)
        val now = System.currentTimeMillis()
        when {
            // First run only: build the index once. After that, opening the app never
            // triggers a heavy full crawl — it just deltas in newly-added content (which
            // stops as soon as it reaches items we already have). A full reconcile (prune
            // removed items, refresh old metadata) is on-demand via Settings ->
            // "Rebuild library now".
            st == null || st.lastFullSyncAt == 0L -> enqueueFullCrawlWork(kind)
            now - st.lastDeltaSyncAt < SyncConstants.DELTA_THROTTLE_MS -> return
            else -> scope.launch { runCatching { deltaSync(kind) } }
        }
    }

    private fun enqueueFullCrawlWork(
        kind: MediaKind,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP
    ) {
        val req = when (kind) {
            MediaKind.MOVIE -> OneTimeWorkRequestBuilder<MovieSyncWorker>()
            MediaKind.SERIES -> OneTimeWorkRequestBuilder<SeriesSyncWorker>()
        }
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(kind.workName, policy, req)
    }

    /**
     * Force a full rebuild of both indexes (Settings "Rebuild library now"). Replaces any
     * in-flight crawl so a fresh full crawl runs regardless of the throttle / auto-update flag.
     */
    fun rebuildLibrary() {
        enqueueFullCrawlWork(MediaKind.MOVIE, ExistingWorkPolicy.REPLACE)
        enqueueFullCrawlWork(MediaKind.SERIES, ExistingWorkPolicy.REPLACE)
    }

    // ---------------------------------------------------------------------------------------
    // Full crawl (resumable, concurrent, prune sweep)
    // ---------------------------------------------------------------------------------------

    /** Heavy resumable crawl. Called from the WorkManager worker. */
    suspend fun fullCrawl(kind: MediaKind) {
        val portal = authManager.currentPortal ?: return
        val portalId = portal.id

        val existing = syncStateDao.get(kind.key)
        // Resume the SAME crawl (stable syncEpoch) if it was interrupted, otherwise start fresh.
        val resume = existing != null && existing.crawlCursorPage > 0 && existing.syncEpoch > 0L
        val stamp = if (resume) existing!!.syncEpoch else System.currentTimeMillis()
        var page = if (resume) existing!!.crawlCursorPage else 1
        var indexed = if (resume) existing!!.itemsIndexed else 0
        var total = existing?.totalItemsRemote ?: 0

        var state = (existing ?: SyncStateEntity(kind = kind.key, status = "IDLE")).copy(
            status = "CRAWLING",
            totalItemsRemote = total,
            itemsIndexed = indexed,
            crawlCursorPage = page,
            syncEpoch = stamp,
            lastError = null
        )
        syncStateDao.upsert(state)

        try {
            val adultIds = resolveAdultCategoryIds(kind, portal.url)
            val semaphore = Semaphore(SyncConstants.CRAWL_CONCURRENCY)

            while (true) {
                val pages = (page until page + SyncConstants.CRAWL_CONCURRENCY).toList()
                val results: List<Pair<Int, VodListData?>> = coroutineScope {
                    pages.map { p ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit { p to fetchList(kind, portal.url, p) }
                        }
                    }.awaitAll()
                }

                val batchTotal = results.mapNotNull { it.second?.total_items }
                    .filter { it > 0 }.maxOrNull()
                if (batchTotal != null && batchTotal > total) total = batchTotal

                val batchData: List<VodData> = results.flatMap { it.second?.data ?: emptyList() }
                if (batchData.isNotEmpty()) {
                    batchData.chunked(SyncConstants.UPSERT_BATCH).forEach { chunk ->
                        upsert(kind, portalId, stamp, adultIds, chunk)
                    }
                    indexed += batchData.size
                }

                val lastPage = page + SyncConstants.CRAWL_CONCURRENCY - 1
                page += SyncConstants.CRAWL_CONCURRENCY

                state = state.copy(
                    totalItemsRemote = total,
                    itemsIndexed = indexed,
                    crawlCursorPage = page
                )
                syncStateDao.upsert(state)

                // Stop when a whole batch came back empty, or we've covered total_items.
                val emptyBatch = results.all { (it.second?.data ?: emptyList()).isEmpty() }
                if (emptyBatch) break
                if (total > 0 && lastPage.toLong() * SyncConstants.PAGE_SIZE >= total) break
            }

            // Tombstone sweep: anything not touched by this crawl was removed upstream.
            prune(kind, portalId, stamp)

            val finalCount = count(kind, portalId)
            syncStateDao.upsert(
                state.copy(
                    status = "IDLE",
                    itemsIndexed = finalCount,
                    crawlCursorPage = 0,
                    lastFullSyncAt = System.currentTimeMillis(),
                    lastError = null
                )
            )
            Log.d("SyncEngine", "fullCrawl(${kind.key}) done: $finalCount rows")
        } catch (e: Exception) {
            // Keep crawlCursorPage so the worker retry resumes from where we stopped.
            syncStateDao.upsert(state.copy(status = "ERROR", lastError = e.message))
            Log.e("SyncEngine", "fullCrawl(${kind.key}) failed", e)
            throw e
        }
    }

    // ---------------------------------------------------------------------------------------
    // Delta sync (front-of-catalogue incremental)
    // ---------------------------------------------------------------------------------------

    /** Lightweight incremental. Runs in the engine scope, not WorkManager. */
    suspend fun deltaSync(kind: MediaKind) {
        val portal = authManager.currentPortal ?: return
        val portalId = portal.id

        val existing = syncStateDao.get(kind.key)
        val stamp = System.currentTimeMillis()
        var state = (existing ?: SyncStateEntity(kind = kind.key, status = "IDLE")).copy(
            status = "DELTA",
            lastError = null
        )
        syncStateDao.upsert(state)

        try {
            val adultIds = resolveAdultCategoryIds(kind, portal.url)
            var pageNum = 1
            while (true) {
                val js = fetchList(kind, portal.url, pageNum) ?: break
                if (js.data.isEmpty()) break

                val ids = js.data.map { it.id }
                val existingIds = existingIds(kind, portalId, ids).toSet()
                upsert(kind, portalId, stamp, adultIds, js.data) // REPLACE refreshes changed metadata

                val newCount = ids.count { it !in existingIds }
                if (newCount == 0) break
                if (pageNum >= SyncConstants.DELTA_MAX_PAGES) break
                pageNum++
            }

            syncStateDao.upsert(
                state.copy(
                    status = "IDLE",
                    itemsIndexed = count(kind, portalId),
                    lastDeltaSyncAt = System.currentTimeMillis(),
                    lastError = null
                )
            )
        } catch (e: Exception) {
            syncStateDao.upsert(state.copy(status = "ERROR", lastError = e.message))
            Log.e("SyncEngine", "deltaSync(${kind.key}) failed", e)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Network + per-kind helpers
    // ---------------------------------------------------------------------------------------

    /** Fetch one list page; on failure (401 / empty), re-handshake once and retry. */
    private suspend fun fetchList(kind: MediaKind, baseUrl: String, page: Int): VodListData? {
        val first = rawList(kind, baseUrl, page)
        first.getOrNull()?.js?.let { return it }

        // Suspected token expiry -> re-handshake once (token reaches the interceptor), retry.
        authMutex.withLock { authManager.reHandshake() }
        return rawList(kind, baseUrl, page).getOrNull()?.js
    }

    private suspend fun rawList(kind: MediaKind, baseUrl: String, page: Int) = when (kind) {
        MediaKind.MOVIE -> api.getVodList(baseUrl, categoryId = null, page = page, sortBy = "added")
        MediaKind.SERIES -> api.getSeriesList(baseUrl, categoryId = null, page = page, sortBy = "added")
    }

    private suspend fun resolveAdultCategoryIds(kind: MediaKind, baseUrl: String): Set<String> {
        val res = when (kind) {
            MediaKind.MOVIE -> api.getVodCategories(baseUrl)
            MediaKind.SERIES -> api.getSeriesCategories(baseUrl)
        }
        val cats = res.getOrNull()?.js ?: emptyList()
        return MediaIndexMapper.adultCategoryIds(cats)
    }

    private suspend fun upsert(
        kind: MediaKind,
        portalId: Long,
        stamp: Long,
        adultIds: Set<String>,
        data: List<VodData>
    ) {
        with(MediaIndexMapper) {
            when (kind) {
                MediaKind.MOVIE ->
                    movieDao.upsert(data.map { it.toMovieEntity(portalId, stamp, adultIds) })
                MediaKind.SERIES ->
                    seriesDao.upsert(data.map { it.toSeriesEntity(portalId, stamp, adultIds) })
            }
        }
    }

    private suspend fun prune(kind: MediaKind, portalId: Long, stamp: Long) = when (kind) {
        MediaKind.MOVIE -> movieDao.prune(portalId, stamp)
        MediaKind.SERIES -> seriesDao.prune(portalId, stamp)
    }

    private suspend fun count(kind: MediaKind, portalId: Long): Int = when (kind) {
        MediaKind.MOVIE -> movieDao.count(portalId)
        MediaKind.SERIES -> seriesDao.count(portalId)
    }

    private suspend fun existingIds(kind: MediaKind, portalId: Long, ids: List<String>): List<String> =
        when (kind) {
            MediaKind.MOVIE -> movieDao.existingIds(portalId, ids)
            MediaKind.SERIES -> seriesDao.existingIds(portalId, ids)
        }
}
