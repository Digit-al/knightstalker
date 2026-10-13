// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.local.sync

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
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
import org.knightstalker.app.data.local.sync.MediaIndexMapper.toXtreamMovieEntity
import org.knightstalker.app.data.local.sync.MediaIndexMapper.toXtreamSeriesEntity
import org.knightstalker.app.data.remote.StalkerAuthManager
import org.knightstalker.app.data.remote.XtreamCodesApi
import org.knightstalker.app.data.remote.dto.VodData
import org.knightstalker.app.data.remote.dto.VodListData
import org.knightstalker.app.data.repository.PortalRepository
import org.knightstalker.app.domain.model.Portal
import org.knightstalker.app.domain.model.PortalType

/**
 * Owns all list-network traffic. Crawls each [MediaKind] into the local Room index and keeps
 * it fresh. The browse path never calls this directly; it only observes [status] and reads
 * Room. Decisions 4/5/6/7/8 from the implementation contract are encoded here.
 */
class SyncEngine(
    private val context: Context,
    private val api: StalkerApi,
    private val authManager: StalkerAuthManager,
    private val xtreamApi: XtreamCodesApi,
    private val movieDao: MovieIndexDao,
    private val seriesDao: SeriesIndexDao,
    private val syncStateDao: SyncStateDao,
    private val preferences: UserPreferences,
    private val portalRepository: PortalRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        /** Unique work name for the periodic Xtream background refresh. */
        const val XTREAM_PERIODIC_WORK_NAME = "sync-xtream-periodic"
    }

    /** Serialises mid-crawl re-handshakes so a token expiry triggers at most one in flight. */
    private val authMutex = Mutex()

    /** UI-facing status stream (status + indexed/total). */
    fun status(kind: MediaKind): Flow<SyncStateEntity?> = syncStateDao.observe(kind.key)

    /**
     * Resolve the active portal, falling back to the persisted "last portal" when the
     * in-memory [StalkerAuthManager.currentPortal] is null. This matters for the
     * WorkManager workers, which may run in a fresh process where no UI has yet
     * re-established the live connection.
     */
    private suspend fun resolvePortal(): Portal? {
        authManager.currentPortal?.let { return it }
        val lastId = preferences.lastPortalId.first() ?: return null
        return portalRepository.getById(lastId)
    }

    // ---------------------------------------------------------------------------------------
    // State machine
    // ---------------------------------------------------------------------------------------

    /** Decide + dispatch per the §7 state machine. Safe to call on every tab open. */
    suspend fun ensureFresh(kind: MediaKind) {
        val portal = authManager.currentPortal ?: return // no active portal -> degrade gracefully
        if (!preferences.autoUpdateOnOpen.first()) return // auto-update disabled -> only manual rebuild
        when (portal.type) {
            PortalType.XTREAM -> ensureFreshXtream(kind, portal)
            PortalType.M3U -> { /* M3U has no crawlable local index */ }
            else -> ensureFreshStalker(kind)
        }
    }

    /**
     * STALKER: first-run full crawl (WorkManager), then front-of-catalogue deltas that are
     * throttled so reopening the tab is cheap. A full reconcile (prune removed items,
     * refresh metadata) is on-demand via Settings -> "Rebuild library now".
     */
    private suspend fun ensureFreshStalker(kind: MediaKind) {
        val st = syncStateDao.get(kind.key)
        val now = System.currentTimeMillis()
        when {
            st == null || st.lastFullSyncAt == 0L -> enqueueFullCrawlWork(kind)
            now - st.lastDeltaSyncAt < SyncConstants.DELTA_THROTTLE_MS -> return
            else -> scope.launch { runCatching { deltaSync(kind) } }
        }
    }

    /**
     * XTREAM: the whole catalogue arrives in a single API call, so there is no incremental
     * delta — a refresh is always a full re-fetch. The first crawl for a portal is
     * offloaded to a WorkManager worker (potentially 200k+ rows); afterwards we honour the
     * user's refresh interval — if the local index is still fresh we do nothing, otherwise
     * we re-crawl in the background. This is what keeps the grid served from Room and
     * avoids re-downloading / re-parsing the full list on every scroll.
     */
    private suspend fun ensureFreshXtream(kind: MediaKind, portal: Portal) {
        // Keep the background refresh schedule aligned with the user's chosen interval.
        val intervalHours = preferences.xtreamRefreshIntervalHours.first()
        scheduleXtreamPeriodicSync(intervalHours)

        val st = syncStateDao.get(kind.key)
        val hasRows = count(kind, portal.id) > 0
        if (!hasRows || st == null) {
            enqueueFullCrawlWork(kind)
        } else {
            val intervalMs = preferences.xtreamRefreshIntervalMs.first()
            val last = st.lastFullSyncAt
            if (last > 0L && System.currentTimeMillis() - last < intervalMs) return // still fresh
            scope.launch { runCatching { fullCrawl(kind) } }
        }
    }

    /**
     * Enqueue (or update) the periodic Xtream refresh so the Room index stays fresh in the
     * background even when Movies / Series is not open. The interval is coerced to a value
     * WorkManager accepts (>= 1 h, whole hours). Uses a unique work name with UPDATE so
     * repeated calls only reschedule rather than stack work.
     */
    fun scheduleXtreamPeriodicSync(intervalHours: Int) {
        val hours = (intervalHours.toLong()).coerceAtLeast(1L)
        val req = PeriodicWorkRequestBuilder<XtreamPeriodicSyncWorker>(hours, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            XTREAM_PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req
        )
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
        // If the active portal is Xtream, keep the background refresh aligned with the
        // user's interval so the freshly-rebuilt index stays fresh afterwards.
        scope.launch {
            val portal = resolvePortal() ?: return@launch
            if (portal.type == PortalType.XTREAM) {
                val hours = preferences.xtreamRefreshIntervalHours.first()
                scheduleXtreamPeriodicSync(hours)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Full crawl (resumable, concurrent, prune sweep)
    // ---------------------------------------------------------------------------------------

    /** Heavy resumable crawl. Called from the WorkManager worker. */
    suspend fun fullCrawl(kind: MediaKind) {
        // Xtream only needs the persisted username/password, so it can resolve its portal
        // from the database even in a fresh worker process (no live connection required).
        val xtream = resolvePortal()?.takeIf { it.type == PortalType.XTREAM }
        if (xtream != null) {
            fullCrawlXtream(kind, xtream, xtream.id)
            return
        }

        // Stalker / M3U need the live connection + in-memory token, so require it.
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
    // Xtream full crawl (single-shot fetch -> chunk -> Room)
    // ---------------------------------------------------------------------------------------

    /**
     * Xtream has no paging and no delta: the whole catalogue arrives in one response.
     * We fetch it once, map every row, and upsert in batches while reporting progress.
     * The fetched list is transient (dropped as soon as the crawl finishes); the durable
     * copy lives in Room, which is what the grid pages through — this is what removes the
     * repeated full-list parse that used to OOM on every scroll page.
     */
    private suspend fun fullCrawlXtream(kind: MediaKind, portal: Portal, portalId: Long) {
        val existing = syncStateDao.get(kind.key)
        val stamp = System.currentTimeMillis()
        var state = (existing ?: SyncStateEntity(kind = kind.key, status = "IDLE")).copy(
            status = "CRAWLING",
            totalItemsRemote = 0,
            itemsIndexed = 0,
            crawlCursorPage = 0,
            syncEpoch = stamp,
            lastError = null
        )
        syncStateDao.upsert(state)

        val adultIds = resolveXtreamAdultCategoryIds(portal)
        try {
            if (kind == MediaKind.MOVIE) {
                val all = xtreamApi.getVodStreams(portal.url, portal.username, portal.password).getOrThrow()
                state = state.copy(totalItemsRemote = all.size, itemsIndexed = 0)
                syncStateDao.upsert(state)
                var indexed = 0
                all.chunked(SyncConstants.UPSERT_BATCH).forEach { chunk ->
                    movieDao.upsert(chunk.map { it.toXtreamMovieEntity(portalId, stamp, adultIds) })
                    indexed += chunk.size
                    state = state.copy(itemsIndexed = indexed)
                    syncStateDao.upsert(state)
                }
            } else {
                val all = xtreamApi.getSeriesList(portal.url, portal.username, portal.password).getOrThrow()
                state = state.copy(totalItemsRemote = all.size, itemsIndexed = 0)
                syncStateDao.upsert(state)
                var indexed = 0
                all.chunked(SyncConstants.UPSERT_BATCH).forEach { chunk ->
                    seriesDao.upsert(chunk.map { it.toXtreamSeriesEntity(portalId, stamp, adultIds) })
                    indexed += chunk.size
                    state = state.copy(itemsIndexed = indexed)
                    syncStateDao.upsert(state)
                }
            }

            // Tombstone sweep: anything not seen in this crawl was removed upstream.
            prune(kind, portalId, stamp)

            val finalCount = count(kind, portalId)
            syncStateDao.upsert(
                state.copy(
                    status = "IDLE",
                    totalItemsRemote = finalCount,
                    itemsIndexed = finalCount,
                    crawlCursorPage = 0,
                    lastFullSyncAt = System.currentTimeMillis(),
                    lastError = null
                )
            )
            Log.d("SyncEngine", "fullCrawlXtream(${kind.key}) done: $finalCount rows")
        } catch (e: Exception) {
            syncStateDao.upsert(state.copy(status = "ERROR", lastError = e.message))
            Log.e("SyncEngine", "fullCrawlXtream(${kind.key}) failed", e)
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
        if (portal.type == PortalType.XTREAM) return // Xtream has no delta; fullCrawlXtream handles it

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
    // Xtream helpers
    // ---------------------------------------------------------------------------------------

    /** Adult-category ids for an Xtream portal (category name contains ADULT/XXX). */
    private suspend fun resolveXtreamAdultCategoryIds(portal: Portal): Set<String> {
        val cats = xtreamApi.getCategories(portal.url, portal.username, portal.password, "movie").getOrNull()
            ?: xtreamApi.getCategories(portal.url, portal.username, portal.password, "series").getOrNull()
            ?: emptyList()
        return cats.mapNotNull { c ->
            val t = c.category_name.uppercase()
            if (t.contains("ADULT") || t.contains("XXX")) c.category_id else null
        }.toSet()
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
