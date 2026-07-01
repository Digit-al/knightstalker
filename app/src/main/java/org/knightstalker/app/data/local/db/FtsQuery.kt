// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.local.db

object FtsQuery {
    /** "amelie jol" -> "amelie* jol*"; blank -> "". Sanitizes FTS metacharacters. */
    fun build(raw: String): String {
        val tokens = raw.trim().lowercase()
            .replace(Regex("[\"*^():]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return ""
        return tokens.joinToString(" ") { "$it*" }   // implicit AND, prefix on every token
    }
}
