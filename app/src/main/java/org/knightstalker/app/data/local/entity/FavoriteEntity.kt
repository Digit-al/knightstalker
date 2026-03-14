// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val channelId: String,
    val portalId: Long,
    val name: String,
    val logo: String?,
    val cmd: String,
    val type: String = "channel", // "channel" or "vod"
    val addedAt: Long = System.currentTimeMillis()
)
