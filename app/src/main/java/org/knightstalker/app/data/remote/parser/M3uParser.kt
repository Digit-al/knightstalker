// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.remote.parser

/**
 * One channel parsed from an M3U/M3U8 playlist.
 *
 *  - [id] is the stream URL itself: for M3U it is the final playable URL.
 *  - [tvgId] is the `tvg-id` attribute when present — the channel's EPG key for
 *    XMLTV matching.
 */
data class M3uChannel(
    val id: String,
    val name: String,
    val tvgId: String?,
    val logo: String?,
    val groupTitle: String?,
    val url: String
)

/**
 * Minimal M3U / M3U8 parser.
 *
 * Recognises `#EXTINF` metadata lines (tvg-id, tvg-logo, group-title, comma-separated name)
 * followed by their stream URL line. Everything else (comments, blank lines) is ignored.
 */
class M3uParser {

    fun parse(content: String): List<M3uChannel> {
        val channels = mutableListOf<M3uChannel>()
        var pendingName: String = ""
        var pendingTvgId: String? = null
        var pendingLogo: String? = null
        var pendingGroup: String? = null
        var pending = false

        fun flush(url: String) {
            if (url.isBlank()) return
            if (pending) {
                channels.add(
                    M3uChannel(
                        id = url,
                        name = pendingName.ifBlank { "Channel ${channels.size + 1}" },
                        tvgId = pendingTvgId?.takeIf { it.isNotBlank() },
                        logo = pendingLogo?.takeIf { it.isNotBlank() },
                        groupTitle = pendingGroup?.takeIf { it.isNotBlank() },
                        url = url
                    )
                )
            }
            pending = false
            pendingName = ""
            pendingTvgId = null
            pendingLogo = null
            pendingGroup = null
        }

        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF")) {
                pending = true
                pendingName = ""
                pendingTvgId = null
                pendingLogo = null
                pendingGroup = null
                val metaStart = line.indexOf(':', 7) // after "#EXTINF:"
                val meta = if (metaStart >= 0) line.substring(metaStart + 1) else ""
                pendingTvgId = attrValue(meta, "tvg-id")
                pendingLogo = attrValue(meta, "tvg-logo")
                pendingGroup = attrValue(meta, "group-title")
                val comma = meta.indexOf(',')
                if (comma >= 0) {
                    pendingName = meta.substring(comma + 1).trim()
                }
            } else if (line.startsWith("#")) {
                continue // #EXTM3U, #KODI, comments...
            } else {
                flush(line)
            }
        }
        return channels
    }

    /** Extracts `name="value"` (single or double quotes) from an M3U metadata blob. */
    private fun attrValue(meta: String, name: String): String? {
        val marker = "$name=\""
        val start = meta.indexOf(marker)
        if (start < 0) return null
        val valueStart = start + marker.length
        val end = meta.indexOf('"', valueStart)
        if (end < 0) return null
        return unescapeXml(meta.substring(valueStart, end))
    }

    /** Unescapes the XML character entities that may appear in M3U attributes. */
    fun unescapeXml(value: String): String {
        if (!value.contains('&')) return value
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '&') {
                val semi = value.indexOf(';', i)
                if (semi > i && semi - i <= 7) {
                    when (value.substring(i + 1, semi)) {
                        "amp" -> sb.append('&')
                        "lt" -> sb.append('<')
                        "gt" -> sb.append('>')
                        "quot" -> sb.append('"')
                        "apos" -> sb.append('\'')
                        else -> {
                            sb.append(value, i, semi + 1)
                            i = semi
                        }
                    }
                    i = semi + 1
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
