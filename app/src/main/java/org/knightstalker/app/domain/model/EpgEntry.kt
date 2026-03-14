// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.domain.model

data class EpgEntry(
    val id: String,
    val channelId: String,
    val title: String,
    val description: String?,
    val startTime: Long,
    val endTime: Long,
    val duration: Int,
    val progressPercent: Float = 0f
)
