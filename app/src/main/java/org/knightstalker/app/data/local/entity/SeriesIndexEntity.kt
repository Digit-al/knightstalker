// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "series_index",
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
data class SeriesIndexEntity(
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
    override val syncEpoch: Long
) : MediaIndexRow
