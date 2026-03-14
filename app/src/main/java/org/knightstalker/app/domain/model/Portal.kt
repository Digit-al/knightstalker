// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.domain.model

data class Portal(
    val id: Long = 0,
    val name: String,
    val url: String,
    val macAddress: String,
    val isConnected: Boolean = false,
    val serverName: String? = null,
    val expDate: String? = null
)
