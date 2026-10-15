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

/** Resolved stream reference for a single episode (Xtream episode id + container ext). */
data class EpisodeRef(
    val episodeId: String,
    val ext: String = "mp4",
    val title: String = ""
)

/** A season of a series, with its play cmd and the episode numbers it holds. */
data class Season(
    val id: String,
    val name: String,
    val cmd: String,
    val episodes: List<Int>,
    val poster: String? = null,
    /** episode number -> stream reference (Xtream episode id + ext). */
    val episodeStreams: Map<Int, EpisodeRef> = emptyMap()
)

/** Watched state of one episode. [fraction] is 0..1 of duration watched. */
data class EpisodeProgress(
    val finished: Boolean,
    val fraction: Float
)

/** The episode the user should watch next in a series (resume an in-progress one,
 *  otherwise the first unwatched). [episodeId] is the Xtream per-episode stream id
 *  (null for portals without per-episode ids). */
data class NextEpisode(
    val seasonIndex: Int,
    val seasonCmd: String,
    val episode: Int,
    val episodes: List<Int>,
    val label: String,
    val resume: Boolean,
    /** Resolved per-episode stream reference (Xtream episode id + ext). */
    val episodeRef: EpisodeRef? = null
)
