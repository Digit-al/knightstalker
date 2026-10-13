// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.local.sync

import org.knightstalker.app.data.local.entity.MovieIndexEntity
import org.knightstalker.app.data.local.entity.SeriesIndexEntity
import org.knightstalker.app.data.remote.XtreamCodesApi
import org.knightstalker.app.data.remote.dto.VodCategoryData
import org.knightstalker.app.data.remote.dto.VodData
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
        syncEpoch = syncEpoch
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
            year = parseYear(releasedate.ifBlank { releaseDate }),
            addedAt = 0L, // Xtream exposes no "added" timestamp
            genresCsv = "",
            durationMin = null,
            posterUrl = image,
            isAdult = computeIsAdult(categoryId, name, 0, adultCategoryIds),
            tmdbId = null,
            actors = cast,
            director = "",
            syncEpoch = syncEpoch
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
            syncEpoch = syncEpoch
        )
    }

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
        syncEpoch = syncEpoch
    )
}
