// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.local.entity

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

@Fts4(contentEntity = SeriesIndexEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "series_fts")
data class SeriesFtsEntity(
    val name: String,
    val actors: String,
    val director: String
)
