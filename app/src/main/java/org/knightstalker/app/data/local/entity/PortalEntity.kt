// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.knightstalker.app.domain.model.PortalType

@Entity(tableName = "portals")
data class PortalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val macAddress: String = "",
    /** "STALKER" | "M3U" | "XTREAM" — see [PortalType]. */
    val type: String = PortalType.STALKER.name,
    val username: String = "",
    val password: String = "",
    val epgUrl: String = "",
    val lastConnected: Long = 0
)
