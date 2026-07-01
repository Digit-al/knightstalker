// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.local.sync

/** The two independently-indexed media kinds. */
enum class MediaKind(val key: String, val workName: String) {
    MOVIE("movie", "sync-movie"),
    SERIES("series", "sync-series")
}
