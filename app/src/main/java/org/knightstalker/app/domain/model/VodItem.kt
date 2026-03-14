// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.domain.model

data class VodItem(
    val id: String,
    val name: String,
    val cmd: String,
    val logo: String?,
    val description: String?,
    val year: String?,
    val genre: String?,
    val rating: String?,
    val duration: String?,
    val director: String?,
    val actors: String?,
    val isFavorite: Boolean = false
)

data class VodCategory(
    val id: String,
    val title: String,
    val alias: String?,
    val count: Int = 0
)
