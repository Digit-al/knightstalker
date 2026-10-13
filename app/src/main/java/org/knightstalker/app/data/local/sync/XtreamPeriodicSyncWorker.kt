// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.local.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import org.knightstalker.app.KnightStalkerApp
import org.knightstalker.app.domain.model.PortalType

/**
 * Periodic background refresh of the Xtream local catalogue (movies + series).
 *
 * Runs on a repeating schedule so the Room index stays fresh even when the user never
 * re-opens Movies / Series. It re-resolves the active Xtream portal from the persisted
 * "last portal" preference (a worker may run in a fresh process where no live connection
 * exists) and, if that portal is Xtream, re-crawls both kinds. The crawl is idempotent
 * (full upsert + prune), so running it a little more often than necessary is harmless.
 */
class XtreamPeriodicSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val module = (applicationContext as KnightStalkerApp).appModule
        val portalId = module.userPreferences.lastPortalId.first() ?: return Result.success()
        val portal = module.portalRepository.getById(portalId) ?: return Result.success()
        if (portal.type != PortalType.XTREAM) return Result.success()

        return try {
            module.syncEngine.fullCrawl(MediaKind.MOVIE)
            module.syncEngine.fullCrawl(MediaKind.SERIES)
            Log.d("XtreamPeriodicSync", "periodic refresh done")
            Result.success()
        } catch (e: Exception) {
            // Don't hammer the panel; retry on the next period instead.
            Log.w("XtreamPeriodicSync", "periodic refresh failed", e)
            Result.success()
        }
    }
}
