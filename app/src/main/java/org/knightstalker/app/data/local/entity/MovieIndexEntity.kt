// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "movie_index",
    primaryKeys = ["portalId", "itemId"],
    indices = [
        Index(value = ["portalId", "categoryId"]),
        Index(value = ["portalId", "isAdult", "rating"]),
        Index(value = ["portalId", "isAdult", "year"]),
        Index(value = ["portalId", "isAdult", "addedAt"]),
        Index(value = ["portalId", "categoryId", "isAdult", "rating"]),
        Index(value = ["portalId", "syncEpoch"])
    ]
)
data class MovieIndexEntity(
    override val portalId: Long,
    override val itemId: String,
    override val name: String,
    override val sortName: String,
    override val cmd: String,
    override val categoryId: String,
    override val rating: Double?,
    override val year: Int?,
    override val addedAt: Long,
    override val genresCsv: String,
    override val durationMin: Int?,
    override val posterUrl: String,
    override val isAdult: Boolean,
    override val tmdbId: String?,
    override val actors: String,
    override val director: String,
    override val syncEpoch: Long,
    /** Container extension (mp4, mkv, …) for building the playable Xtream URL. Empty for M3U. */
    @androidx.room.ColumnInfo(defaultValue = "")
    override val ext: String = ""
) : MediaIndexRow
