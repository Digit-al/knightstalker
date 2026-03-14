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
