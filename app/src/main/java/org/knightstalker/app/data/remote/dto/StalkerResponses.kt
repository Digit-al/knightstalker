// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class StalkerResponse<T>(
    val js: T? = null,
    val text: String? = null
)

@Serializable
data class HandshakeData(
    val token: String = "",
    val random: String = ""
)

@Serializable
data class ProfileData(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("login") val login: String = "",
    @SerialName("status") val status: Int = 0,
    @SerialName("blocked") val blocked: Int = 0,
    @SerialName("expire_billing_date") val expireBillingDate: String = "",
    @SerialName("tariff_plan_name") val tariffPlanName: String = ""
)

@Serializable
data class GenreData(
    val id: String = "",
    val title: String = "",
    val alias: String = "",
    val number: String = "",
    @SerialName("censored") val censored: Int = 0
)

@Serializable
data class ChannelListData(
    val total_items: Int = 0,
    val max_page_items: Int = 0,
    val data: List<ChannelData> = emptyList()
)

@Serializable
data class ChannelData(
    val id: String = "",
    val name: String = "",
    val number: String = "0",
    val cmd: String = "",
    val logo: String = "",
    @SerialName("tv_genre_id") val tvGenreId: String = "",
    @SerialName("use_http_tmp_link") val useHttpTmpLink: Int = 0,
    @SerialName("allow_pvr") val allowPvr: Int = 0,
    @SerialName("epg") val epg: JsonElement? = null
)

@Serializable
data class CreateLinkData(
    val cmd: String = ""
)

@Serializable
data class EpgListData(
    val data: List<EpgData> = emptyList()
)

@Serializable
data class EpgData(
    val id: String = "",
    @SerialName("ch_id") val chId: String = "",
    val name: String = "",
    @SerialName("descr") val description: String = "",
    @SerialName("t_time") val startTime: String = "",
    @SerialName("t_time_duration") val duration: String = "",
    @SerialName("time") val timeUnix: Long = 0,
    @SerialName("time_to") val timeToUnix: Long = 0,
    @SerialName("duration") val durationSec: Int = 0
)

@Serializable
data class ShortEpgData(
    @SerialName("data") val entries: List<ShortEpgEntry> = emptyList()
)

@Serializable
data class ShortEpgEntry(
    val name: String = "",
    @SerialName("descr") val description: String = "",
    @SerialName("t_time") val startTime: String = "",
    @SerialName("t_time_duration") val duration: String = "",
    @SerialName("time") val timeUnix: Long = 0,
    @SerialName("time_to") val timeToUnix: Long = 0,
    @SerialName("duration") val durationSec: Int = 0
)

@Serializable
data class VodCategoryData(
    val id: String = "",
    val title: String = "",
    val alias: String = "",
    @SerialName("censored") val censored: Int = 0
)

@Serializable
data class VodListData(
    val total_items: Int = 0,
    val max_page_items: Int = 0,
    val data: List<VodData> = emptyList()
)

@Serializable
data class VodData(
    val id: String = "",
    val name: String = "",
    val cmd: String = "",
    @SerialName("screenshot_uri") val screenshotUri: String = "",
    @SerialName("description") val description: String = "",
    val year: String = "",
    @SerialName("genre_str") val genreStr: String = "",
    @SerialName("rating_imdb") val ratingImdb: String = "",
    @SerialName("rating_kinopoisk") val ratingKinopoisk: String = "",
    val time: String = "",
    val director: String = "",
    val actors: String = "",
    // --- added for the local index ---
    @SerialName("o_name") val oName: String = "",
    @SerialName("category_id") val categoryId: String = "",
    @SerialName("added") val added: String = "",
    @SerialName("rate") val rate: String = "",
    @SerialName("tmdb_id") val tmdbId: String = "",
    @SerialName("is_series") val isSeries: Int = 0,
    @SerialName("is_movie") val isMovie: Int = 0,
    @SerialName("censored") val censored: Int = 0
)

@Serializable
data class SeriesSeasonListData(
    val total_items: Int = 0,
    val max_page_items: Int = 0,
    val data: List<SeasonData> = emptyList()
)

@Serializable
data class SeasonData(
    val id: String = "",
    val name: String = "",
    val cmd: String = "",
    // Season poster (usually a TMDB image URL); reused as an episode thumbnail.
    @SerialName("screenshot_uri") val screenshotUri: String = "",
    // Episode numbers available in this season, e.g. [1, 2, 3, ...].
    @SerialName("series") val episodes: List<Int> = emptyList()
)
