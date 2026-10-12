// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.remote.parser

import org.knightstalker.app.domain.model.EpgEntry
import org.w3c.dom.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.text.MatchResult

/**
 * Parser for XMLTV EPG files / feeds.
 *
 * Input is the raw XML text of a `tv` document:
 * ```xml
 * <tv>
 *   <channel id="france2.france.tv">France 2</channel>
 *   <programme start="20250101190000 +0100" stop="..." channel="france2.france.tv">
 *     <title lang="fr">...</title>
 *     <desc>...</desc>
 *   </programme>
 * </tv>
 * ```
 *
 * [parse] returns EPG entries keyed by the `channel` attribute. [parseChannels]
 * returns the declared `<channel>` elements, useful to match a playlist's
 * `tvg-id` / Xtream `epg_channel_id` values against the feed.
 */
class XmltvParser {

    data class FeedChannel(
        val id: String,
        val displayName: String?
    )

    /**
     * Parses the feed. [day] ("yyyy-MM-dd") restricts entries to that day (local
     * time) when non-null; null returns every entry.
     */
    fun parse(xml: String, day: String? = null): Map<String, List<EpgEntry>> {
        if (xml.isBlank()) return emptyMap()
        val dbf = newSecureFactory()
        val doc = dbf.newDocumentBuilder().parse(xml.byteInputStream())
        doc.documentElement.normalize()

        val dayStart: Long? = day?.let {
            try {
                val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                fmt.timeZone = java.util.TimeZone.getDefault()
                fmt.parse(it)?.time?.div(1000)
            } catch (_: Exception) {
                null
            }
        }
        val dayEnd = dayStart?.plus(86_400)

        val out: MutableMap<String, MutableList<EpgEntry>> = LinkedHashMap()
        val programmes = doc.getElementsByTagName("programme")

        for (i in 0 until programmes.length) {
            val el = programmes.item(i) as? Element ?: continue
            val channelId = el.getAttribute("channel")
            if (channelId.isBlank()) continue

            val start: Long = parseXmltvTime(el.getAttribute("start")) ?: continue
            val stopFromAttr: Long? = parseXmltvTime(el.getAttribute("stop"))
            val stop: Long = stopFromAttr
                ?: parseXmltvTime(el.getAttribute("stopdate"))
                ?: (start + (parseXmltvDuration(el.getAttribute("duration")) ?: 3600L))

            // Day filtering: keep entries overlapping the requested day.
            if (dayStart != null && dayEnd != null) {
                if (stop <= dayStart || start >= dayEnd) continue
            }

            val title = textOf(el, "title")?.takeIf { it.isNotBlank() } ?: "Programme"
            val desc = textOf(el, "desc")?.takeIf { it.isNotBlank() }

            val entry = EpgEntry(
                id = "${channelId}_${start}",
                channelId = channelId,
                title = title,
                description = desc,
                startTime = start,
                endTime = stop,
                duration = ((stop - start) / 60).toInt()
            )
            val list: MutableList<EpgEntry> = out.getOrPut(channelId) { mutableListOf() }
            list.add(entry)
        }

        // Sort each channel's schedule chronologically.
        out.values.forEach { it.sortBy { e -> e.startTime } }
        return out
    }

    /** Declared `<channel>` elements of the feed. */
    fun parseChannels(xml: String): List<FeedChannel> {
        if (xml.isBlank()) return emptyList()
        val dbf = newSecureFactory()
        val doc = dbf.newDocumentBuilder().parse(xml.byteInputStream())
        doc.documentElement.normalize()
        val channels = doc.getElementsByTagName("channel")
        val result = ArrayList<FeedChannel>(channels.length)
        for (i in 0 until channels.length) {
            val el = channels.item(i) as? Element ?: continue
            val id = el.getAttribute("id")
            if (id.isBlank()) continue
            val display = el.getAttribute("display-name")
            result.add(FeedChannel(id, display.takeIf { it.isNotBlank() }))
        }
        return result
    }

    private fun newSecureFactory(): DocumentBuilderFactory {
        return DocumentBuilderFactory.newInstance().apply {
            // Harden against XXE: no DTDs / external entities.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
    }

    private fun textOf(parent: Element, tag: String): String? {
        val nodes = parent.getElementsByTagName(tag)
        if (nodes.length == 0) return null
        return (nodes.item(0) as? Element)?.textContent?.trim()
    }

    private fun matchClassic(v: String): MatchResult? =
        Regex("^(\\d{4})(\\d{2})(\\d{2})(\\d{2})?(\\d{2})?(\\d{2})?\\s*([+-]\\d{2}:?\\d{2})?$")
            .matchEntire(v)

    private fun matchIso(v: String): MatchResult? =
        Regex("^(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2}):(\\d{2})(?:\\.\\d+)?\\s*([+-]\\d{2}:?\\d{2})?$")
            .matchEntire(v)

    /**
     * Parses XMLTV timestamps, returning Unix seconds (UTC).
     * Supported layouts:
     *  - classic:   "20250101190000 +0100"
     *  - ISO-ish:   "2025-01-01 19:00:00.000 +0100" / "2025-01-01T19:00:00+01:00"
     * When the feed omits an offset, the time is interpreted in the device's
     * local zone (standard XMLTV fallback).
     */
    fun parseXmltvTime(value: String): Long? {
        val v = value.trim()
        if (v.isEmpty()) return null

        val classic = matchClassic(v)
        val iso = matchIso(v)
        val m = classic ?: iso ?: return null
        val g = m.groupValues

        val year = g[1].toIntOrNull() ?: return null
        val month = g[2].toIntOrNull() ?: return null
        val day = g[3].toIntOrNull() ?: return null
        val hour = g[4].toIntOrNull() ?: 0
        val minute = g[5].toIntOrNull() ?: 0
        val second = g[6].toIntOrNull() ?: 0
        val offset = g[7].takeIf { it.isNotEmpty() }

        // Wall-clock in the device zone -> naive epoch (device offset removed),
        // then apply the feed's own offset if present.
        val cal = Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
        }
        val zoneOffsetMs = cal.get(Calendar.ZONE_OFFSET).toLong() * 1000L +
            cal.get(Calendar.DST_OFFSET).toLong() * 1000L
        var epochMillis = cal.timeInMillis - zoneOffsetMs
        if (offset != null) {
            // UTC = wall clock - feed offset
            val sign = if (offset.startsWith("-")) -1L else 1L
            val digits = offset.replace("-", "").replace(":", "").trim()
            val oh = digits.take(2).toIntOrNull() ?: 0
            val om = digits.drop(2).take(2).toIntOrNull() ?: 0
            epochMillis -= sign * (oh * 3600L + om * 60L) * 1000L
        }
        return epochMillis / 1000L
    }

    /** Parses XMLTV `duration` seconds, e.g. "7200" or "01:20:00". */
    private fun parseXmltvDuration(value: String): Long? {
        if (value.isBlank()) return null
        value.trim().toLongOrNull()?.let { return it }
        val parts = value.split(":")
        if (parts.size == 3) {
            val h = parts[0].toIntOrNull() ?: return null
            val mm = parts[1].toIntOrNull() ?: return null
            val s = parts[2].toIntOrNull() ?: return null
            return h * 3600L + mm * 60L + s
        }
        return null
    }
}
