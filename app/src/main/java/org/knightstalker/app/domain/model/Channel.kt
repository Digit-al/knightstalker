// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.domain.model

data class Channel(
    val id: String,
    val name: String,
    val number: Int,
    val cmd: String,
    val logo: String?,
    val genre: String?,
    val genreId: String?,
    /**
     * EPG lookup key for XMLTV feeds (M3U/XTREAM portals):
     * `tvg-id` for M3U, `epg_channel_id` for Xtream. Null for Stalker channels
     * (which resolve EPG through the Stalker API by [id]).
     */
    val epgKey: String? = null,
    val nowPlaying: EpgEntry? = null,
    val upNext: EpgEntry? = null,
    val isFavorite: Boolean = false
)

data class ChannelCategory(
    val id: String,
    val title: String,
    val alias: String?,
    val channelCount: Int = 0
)
