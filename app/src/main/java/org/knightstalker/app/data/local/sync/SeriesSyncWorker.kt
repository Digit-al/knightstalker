// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.local.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.knightstalker.app.KnightStalkerApp

/** Long-running resumable full crawl for series (unique work "sync-series"). */
class SeriesSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val engine = (applicationContext as KnightStalkerApp).appModule.syncEngine
        return try {
            engine.fullCrawl(MediaKind.SERIES)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
