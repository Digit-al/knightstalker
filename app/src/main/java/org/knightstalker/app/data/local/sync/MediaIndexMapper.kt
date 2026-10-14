// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.local.sync

import org.knightstalker.app.data.local.entity.MovieIndexEntity
import org.knightstalker.app.data.local.entity.SeriesIndexEntity
import org.knightstalker.app.data.remote.XtreamCodesApi
import org.knightstalker.app.data.remote.dto.VodCategoryData
import org.knightstalker.app.data.remote.dto.VodData
import org.knightstalker.app.data.remote.parser.M3uChannel
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * DTO -> index entity mapping plus the §8 normalization helpers. All functions are
 * pure (no Android / IO dependencies) so they can be unit-tested directly.
 */
object MediaIndexMapper {

    private val addedFormat: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val leadingMarkers = Regex("^(?:\\s*\\|[^|]*\\|)+\\s*")
    private val combiningMarks = Regex("\\p{Mn}+")
    private val leadingYear = Regex("^(\\d{4})")
    private val leadingDigits = Regex("^(\\d+)")

    /**
     * rating_imdb -> rating_kinopoisk; "N/A"/""/"0"/non-numeric -> null; accepts comma
     * decimals; clamps 0..10.
     *
     * NOTE: the portal's `rate` field is deliberately NOT used — it is an internal counter,
     * not a review score, and for series it is usually a bogus "10" that surfaced as a fake
     * ★10.0 on every show. `rate` is still accepted as a parameter for call-site symmetry.
     */
    fun parseRating(imdb: String, kinopoisk: String, rate: String): Double? {
        for (candidate in listOf(imdb, kinopoisk)) {
            val trimmed = candidate.trim()
            if (trimmed.isEmpty() || trimmed.equals("N/A", ignoreCase = true)) continue
            val value = trimmed.replace(',', '.').toDoubleOrNull() ?: continue
            if (value <= 0.0) continue
            return value.coerceIn(0.0, 10.0)
        }
        return null
    }

    /** Leading 4 digits of `year` ("2022" or "2022-12-14") -> Int, else null. */
    fun parseYear(year: String): Int? =
        leadingYear.find(year.trim())?.groupValues?.get(1)?.toIntOrNull()

    /** "yyyy-MM-dd HH:mm:ss" -> epoch millis (portal-local tz); fallback 0L on parse fail. */
    fun parseAddedAt(added: String): Long {
        val trimmed = added.trim()
        if (trimmed.isEmpty()) return 0L
        return try {
            addedFormat.parse(trimmed)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Xtream `added` is a raw Unix epoch (seconds). Convert to epoch millis, 0 on bad input.
     */
    fun parseXtreamAdded(added: String): Long {
        val trimmed = added.trim()
        if (trimmed.isEmpty()) return 0L
        val secs = trimmed.toLongOrNull() ?: return 0L
        if (secs <= 0L) return 0L
        // A bare value below ~1e11 is a Unix epoch in seconds -> convert to millis;
        // anything larger is already epoch millis. Covers 1973..~5138 either way.
        return if (secs < 100_000_000_000L) secs * 1000L else secs
    }

    /**
     * Leading 4-digit year from a bare title when the portal sends no release date.
     * e.g. "Water For Elephants (2011)" or "The Matrix 1999" -> 2011 / 1999.
     */
    private fun yearFromName(name: String): Int? {
        val m = Regex("\\((\\d{4})\\)").find(name) ?: return null
        val y = m.groupValues[1].toIntOrNull() ?: return null
        return if (y in 1900..2100) y else null
    }

    /** `time` (minutes string) -> Int, else null. */
    fun parseDurationMin(time: String): Int? =
        leadingDigits.find(time.trim())?.groupValues?.get(1)?.toIntOrNull()

    /** categoryId in adultCategoryIds OR name contains "|xXx"/"xXx|" (ci) OR censored == 1. */
    fun computeIsAdult(
        categoryId: String,
        name: String,
        censored: Int,
        adultCategoryIds: Set<String>
    ): Boolean {
        if (censored == 1) return true
        if (categoryId in adultCategoryIds) return true
        val lower = name.lowercase()
        return lower.contains("|xxx") || lower.contains("xxx|")
    }

    /** Strip leading |..| markers, strip accents (NFD + drop combining marks), lowercase, trim. */
    fun normalizeSortName(name: String): String {
        val withoutMarkers = leadingMarkers.replaceFirst(name, "")
        val decomposed = Normalizer.normalize(withoutMarkers, Normalizer.Form.NFD)
        val stripped = combiningMarks.replace(decomposed, "")
        return stripped.lowercase().trim()
    }

    /** Resolve adult category ids from a category list: title contains "ADULT" or "XXX" (ci). */
    fun adultCategoryIds(categories: List<VodCategoryData>): Set<String> =
        categories.asSequence()
            .filter {
                val t = it.title.uppercase()
                t.contains("ADULT") || t.contains("XXX")
            }
            .map { it.id }
            .toSet()

    fun VodData.toMovieEntity(
        portalId: Long,
        syncEpoch: Long,
        adultCategoryIds: Set<String>
    ): MovieIndexEntity = MovieIndexEntity(
        portalId = portalId,
        itemId = id,
        name = name,
        sortName = normalizeSortName(name),
        cmd = cmd,
        categoryId = categoryId,
        rating = parseRating(ratingImdb, ratingKinopoisk, rate),
        year = parseYear(year),
        addedAt = parseAddedAt(added),
        genresCsv = genreStr,
        durationMin = parseDurationMin(time),
        posterUrl = screenshotUri,
        isAdult = computeIsAdult(categoryId, name, censored, adultCategoryIds),
        tmdbId = tmdbId.ifEmpty { null },
        actors = actors,
        director = director,
        syncEpoch = syncEpoch,
        ext = ""
    )

    /**
     * Xtream `rating` is a 0-10 string ("7.5", "8", "N/A", ""). The 5-based variant
     * (`rating_5based`) is intentionally ignored — `rating` is already on the 0-10 scale.
     */
    fun parseXtreamRating(rating: String): Double? {
        val trimmed = rating.trim()
        if (trimmed.isEmpty() || trimmed.equals("N/A", ignoreCase = true)) return null
        val value = trimmed.replace(',', '.').toDoubleOrNull() ?: return null
        if (value <= 0.0) return null
        return value.coerceIn(0.0, 10.0)
    }

    /** Map a Xtream on-demand stream to a movie index row. */
    fun XtreamCodesApi.VodItem.toXtreamMovieEntity(
        portalId: Long,
        syncEpoch: Long,
        adultCategoryIds: Set<String>
    ): MovieIndexEntity {
        val categoryId = category_id.ifBlank { category_ids?.firstOrNull().orEmpty() }
        return MovieIndexEntity(
            portalId = portalId,
            itemId = stream_id,
            name = name,
            sortName = normalizeSortName(name),
            cmd = stream_id,
            categoryId = categoryId,
            rating = parseXtreamRating(rating),
            year = parseYear(releasedate.ifBlank { releaseDate }) ?: yearFromName(name),
            addedAt = parseXtreamAdded(added),
            genresCsv = "",
            durationMin = null,
            posterUrl = image,
            isAdult = computeIsAdult(categoryId, name, 0, adultCategoryIds),
            tmdbId = tmdb.ifBlank { null },
            actors = cast,
            director = "",
            syncEpoch = syncEpoch,
            ext = container_extension.ifBlank { "mp4" }
        )
    }

    /** Map a Xtream series to a series index row. */
    fun XtreamCodesApi.SeriesItem.toXtreamSeriesEntity(
        portalId: Long,
        syncEpoch: Long,
        adultCategoryIds: Set<String>
    ): SeriesIndexEntity {
        val categoryId = category_id.ifBlank { "" }
        return SeriesIndexEntity(
            portalId = portalId,
            itemId = series_id,
            name = name,
            sortName = normalizeSortName(name),
            cmd = series_id,
            categoryId = categoryId,
            rating = parseXtreamRating(rating),
            year = parseYear(releasedate.ifBlank { releaseDate }),
            addedAt = 0L,
            genresCsv = genre,
            durationMin = null,
            posterUrl = image,
            isAdult = computeIsAdult(categoryId, name, 0, adultCategoryIds),
            tmdbId = null,
            actors = cast,
            director = director,
            syncEpoch = syncEpoch,
            // Xtream series have no single container extension — it is per-episode.
            ext = ""
        )
    }

    /**
     * An M3U playlist has no series concept — every entry (live, VOD, movie) is a
     * stream URL. For the Movies grid we index the whole playlist as "movies".
     * The crawl timestamp doubles as `addedAt` so "Date added" sort is stable and
     * meaningful (all rows land with the crawl time; without it every row would
     * tie at 0 and fall back to name order).
     */
    fun M3uChannel.toM3uMovieEntity(portalId: Long, syncEpoch: Long): MovieIndexEntity = MovieIndexEntity(
        portalId = portalId,
        itemId = id,
        name = name,
        sortName = normalizeSortName(name),
        cmd = url,
        categoryId = groupTitle ?: "General",
        rating = null,
        year = null,
        addedAt = syncEpoch,
        genresCsv = "",
        durationMin = null,
        posterUrl = logo.orEmpty(),
        isAdult = false,
        tmdbId = tvgId,
        actors = "",
        director = "",
        syncEpoch = syncEpoch,
        ext = ""
    )

    fun VodData.toSeriesEntity(
        portalId: Long,
        syncEpoch: Long,
        adultCategoryIds: Set<String>
    ): SeriesIndexEntity = SeriesIndexEntity(
        portalId = portalId,
        itemId = id,
        name = name,
        sortName = normalizeSortName(name),
        cmd = cmd,
        categoryId = categoryId,
        rating = parseRating(ratingImdb, ratingKinopoisk, rate),
        year = parseYear(year),
        addedAt = parseAddedAt(added),
        genresCsv = genreStr,
        durationMin = parseDurationMin(time),
        posterUrl = screenshotUri,
        isAdult = computeIsAdult(categoryId, name, censored, adultCategoryIds),
        tmdbId = tmdbId.ifEmpty { null },
        actors = actors,
        director = director,
        syncEpoch = syncEpoch,
        ext = ""
    )
}
