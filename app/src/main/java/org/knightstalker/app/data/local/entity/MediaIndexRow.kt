// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.local.entity

/**
 * Shared column contract for the local media index rows. Movies and Series are
 * stored in separate tables/entities but expose an identical column set so the
 * DTO->entity mapper and any consumer code can be written once.
 */
interface MediaIndexRow {
    val portalId: Long
    val itemId: String
    val name: String
    val sortName: String
    val cmd: String
    val categoryId: String
    val rating: Double?
    val year: Int?
    val addedAt: Long
    val genresCsv: String
    val durationMin: Int?
    val posterUrl: String
    val isAdult: Boolean
    val tmdbId: String?
    val actors: String
    val director: String
    val syncEpoch: Long
    /** Container extension (mp4, mkv, ts, m3u8 …) as reported by the portal. Empty for non-Xtream. */
    val ext: String
}
