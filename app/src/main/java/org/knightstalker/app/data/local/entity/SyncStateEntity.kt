// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_sync_state")
data class SyncStateEntity(
    @PrimaryKey val kind: String,        // "movie" | "series"
    val status: String,                  // "IDLE" | "CRAWLING" | "DELTA" | "ERROR"
    val totalItemsRemote: Int = 0,
    val itemsIndexed: Int = 0,
    val crawlCursorPage: Int = 0,        // resume point; 0 = fresh
    val lastFullSyncAt: Long = 0,        // 0 = never -> forces first-run full crawl
    val lastDeltaSyncAt: Long = 0,
    val syncEpoch: Long = 0,
    val lastError: String? = null,
    /**
     * Schema/format generation of the rows last written for this kind. Bumped in code when
     * the crawl starts storing new fields (e.g. TMDB id, year, poster); a stored value
     * below [CRAWL_FORMAT_VERSION] forces one full re-crawl so the new columns fill in.
     */
    val crawlFormatVersion: Int = 0
)
