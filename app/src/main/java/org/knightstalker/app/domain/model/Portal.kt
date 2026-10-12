// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.domain.model

/**
 * The kind of source backing a [Portal].
 *
 *  - [STALKER]: a Stalker Middleware / Ministra portal (URL + MAC address), the original
 *    supported source.
 *  - [M3U]: a classic M3U/M3U8 playlist file (remote URL) with direct stream URLs.
 *  - [XTREAM]: an Xtream Codes compatible panel (URL + username + password).
 */
enum class PortalType { STALKER, M3U, XTREAM }

data class Portal(
    val id: Long = 0,
    val name: String,
    val url: String,
    val macAddress: String = "",
    /** Source type. Defaults to STALKER for portals created before M3U/XTream support. */
    val type: PortalType = PortalType.STALKER,
    /** Xtream Codes username (ignored for the other types). */
    val username: String = "",
    /** Xtream Codes password (ignored for the other types). */
    val password: String = "",
    /** Optional XMLTV EPG feed URL applied to this portal (any type). */
    val epgUrl: String = "",
    val isConnected: Boolean = false,
    val serverName: String? = null,
    val expDate: String? = null
) {
    val isStalker: Boolean get() = type == PortalType.STALKER
    val isM3U: Boolean get() = type == PortalType.M3U
    val isXtream: Boolean get() = type == PortalType.XTREAM
}
