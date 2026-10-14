// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.domain.model

data class VodItem(
    val id: String,
    val name: String,
    val cmd: String,
    val logo: String?,
    val description: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val rating: String? = null,
    val duration: String? = null,
    val director: String? = null,
    val actors: String? = null,
    val isFavorite: Boolean = false,
    /** Container extension (mp4, mkv, …) for building the playable Xtream URL. */
    val ext: String = "",
    /** TMDB movie/series ID when the portal supplies one — used to enrich details. */
    val tmdbId: String? = null
)

data class VodCategory(
    val id: String,
    val title: String,
    val alias: String?,
    val count: Int = 0
)

/** A season of a series, with its play cmd and the episode numbers it holds. */
data class Season(
    val id: String,
    val name: String,
    val cmd: String,
    val episodes: List<Int>,
    val poster: String? = null
)

/** Watched state of one episode. [fraction] is 0..1 of duration watched. */
data class EpisodeProgress(
    val finished: Boolean,
    val fraction: Float
)

/** The episode the user should watch next in a series (resume an in-progress one,
 *  otherwise the first unwatched). */
data class NextEpisode(
    val seasonIndex: Int,
    val seasonCmd: String,
    val episode: Int,
    val episodes: List<Int>,
    val label: String,
    val resume: Boolean
)
