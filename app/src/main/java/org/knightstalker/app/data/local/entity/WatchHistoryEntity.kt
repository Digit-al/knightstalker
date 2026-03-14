// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watch_history")
data class WatchHistoryEntity(
    @PrimaryKey val itemId: String,
    val portalId: Long,
    val name: String,
    val logo: String?,
    val cmd: String,
    val type: String, // "itv" or "vod"
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val progressPercent: Float = 0f,
    val isFinished: Boolean = false,
    val lastWatched: Long = System.currentTimeMillis()
)
