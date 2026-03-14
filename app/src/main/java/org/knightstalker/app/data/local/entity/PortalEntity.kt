// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "portals")
data class PortalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val macAddress: String,
    val lastConnected: Long = 0
)
