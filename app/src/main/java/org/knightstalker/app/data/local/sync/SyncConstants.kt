// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.local.sync

/** Tuning constants for the crawl / delta sync engine. */
object SyncConstants {
    const val PAGE_SIZE = 14                              // portal max_page_items
    const val CRAWL_CONCURRENCY = 8                       // pages in flight during full crawl
    const val UPSERT_BATCH = 500                          // rows per Room transaction
    const val DELTA_MAX_PAGES = 40                        // delta safety cap
    const val DELTA_THROTTLE_MS = 15L * 60_000L           // 15 min between deltas
    const val FULL_RECONCILE_MS = 7L * 24L * 3_600_000L   // 7 days
}
