// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.remote.parser

import android.util.Log
import org.knightstalker.app.domain.model.EpgEntry
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.xml.parsers.SAXParserFactory
import kotlin.text.MatchResult

/**
 * Parser for XMLTV EPG files / feeds.
 *
 * Uses a streaming SAX parser so that large feeds (tens of MB) can be parsed
 * without loading the entire DOM tree into memory — a 67 MB feed that would
 * otherwise OOM a phone now parses with a flat, small result map.
 *
 * [parse] returns EPG entries keyed by the `channel` attribute.
 * [parseChannels] returns the declared `<channel>` elements.
 * [parseAll] does both in a single pass (preferred).
 */
class XmltvParser {

    data class FeedChannel(
        val id: String,
        val displayName: String?
    )

    /** Result of a full feed parse: per-channel schedules + declared channels. */
    data class ParseResult(
        val schedules: Map<String, List<EpgEntry>>,
        val channels: List<FeedChannel>
    )

    /**
     * Parses the feed in a single streaming SAX pass.
     *
     * [day] ("yyyy-MM-dd") restricts entries to that day (local time) when
     * non-null; null returns every entry.
     */
    fun parseAll(xml: String, day: String? = null): ParseResult {
        if (xml.isBlank()) return ParseResult(emptyMap(), emptyList())

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
        val channels = mutableListOf<FeedChannel>()

        // <programme> accumulation state
        var progChannel: String? = null
        var progStart: Long? = null
        var progStop: Long? = null
        var progTitle: StringBuilder? = null
        var progDesc: StringBuilder? = null
        var inTitle = false
        var inDesc = false

        // <channel> accumulation state
        var chanId: String? = null
        var chanName: StringBuilder? = null
        var inChanName = false

        try {
            // XMLTV files carry a standard DOCTYPE — accept it, but never
            // fetch the referenced DTD from the network (XXE protection).
            // Individual features may be unsupported on some platforms; fall
            // back gracefully.
            val factory = SAXParserFactory.newInstance()
            val secureFactory = try {
                factory.apply {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", false)
                    setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                }
            } catch (_: Exception) { factory }
            secureFactory.isNamespaceAware = false
            secureFactory.isXIncludeAware = false
            val parser = secureFactory.newSAXParser()

            parser.parse(
                InputSource(xml.byteInputStream()),
                object : DefaultHandler() {

                    override fun startElement(
                        uri: String,
                        localName: String,
                        qName: String,
                        attrs: Attributes
                    ) {
                        // Use localName so namespace-prefixed elements still match.
                        when (localName) {
                            "channel" -> {
                                chanId = attrs.getValue("id")
                            }

                            "display-name" -> {
                                if (chanId != null && !inChanName) {
                                    inChanName = true
                                    chanName = StringBuilder()
                                }
                            }

                            "programme" -> {
                                val ch = attrs.getValue("channel")?.takeIf { it.isNotBlank() }
                                val startStr = attrs.getValue("start")
                                val stopStr = attrs.getValue("stop")
                                val stopDateStr = attrs.getValue("stopdate")
                                val durationStr = attrs.getValue("duration")

                                val parsedStart =
                                    startStr?.takeIf { it.isNotBlank() }?.let { parseXmltvTime(it) }

                                val parsedStop =
                                    (stopStr?.takeIf { it.isNotBlank() }?.let { parseXmltvTime(it) })
                                        ?: (stopDateStr?.takeIf { it.isNotBlank() }?.let { parseXmltvTime(it) })
                                        ?: if (parsedStart != null) {
                                            val dur = durationStr?.let { parseXmltvDuration(it) } ?: 3600L
                                            parsedStart + dur
                                        } else null

                                // Only track entries that have both a channel id and a valid start.
                                progChannel = if (ch != null && parsedStart != null) ch else null
                                progStart = parsedStart
                                progStop = parsedStop
                                progTitle = StringBuilder()
                                progDesc = null
                                inTitle = false
                                inDesc = false
                            }

                            "title" -> {
                                if (progChannel != null) inTitle = true
                            }

                            "desc" -> {
                                if (progChannel != null) {
                                    inDesc = true
                                    progDesc = StringBuilder()
                                }
                            }
                        }
                    }

                    override fun characters(ch: CharArray, start: Int, length: Int) {
                        when {
                            inTitle -> progTitle?.append(ch, start, length)
                            inDesc -> progDesc?.append(ch, start, length)
                            inChanName -> chanName?.append(ch, start, length)
                        }
                    }

                    override fun endElement(
                        uri: String,
                        localName: String,
                        qName: String
                    ) {
                        when (localName) {
                            "title" -> {
                                inTitle = false
                            }

                            "desc" -> {
                                inDesc = false
                            }

                            "display-name" -> {
                                inChanName = false
                            }

                            "channel" -> {
                                val id = chanId
                                if (id != null && id.isNotBlank()) {
                                    val name = chanName?.toString()?.trim()?.takeIf { it.isNotBlank() }
                                    channels.add(FeedChannel(id, name))
                                }
                                chanId = null
                                chanName = null
                                inChanName = false
                            }

                            "programme" -> {
                                val channelId = progChannel
                                val start = progStart
                                val stop = progStop
                                val title = progTitle?.toString()?.trim()
                                    ?.takeIf { it.isNotBlank() } ?: "Programme"
                                val desc = progDesc?.toString()?.trim()?.takeIf { it.isNotBlank() }

                                if (channelId != null && start != null && stop != null) {
                                    // Keep entries overlapping the requested day.
                                    if (dayStart == null || dayEnd == null ||
                                        (stop > dayStart && start < dayEnd)
                                    ) {
                                        val entry = EpgEntry(
                                            id = "${channelId}_${start}",
                                            channelId = channelId,
                                            title = title,
                                            description = desc,
                                            startTime = start,
                                            endTime = stop,
                                            duration = ((stop - start) / 60).toInt()
                                        )
                                        out.getOrPut(channelId) { mutableListOf() }.add(entry)
                                    }
                                }

                                // Reset for the next <programme> element.
                                progChannel = null
                                progStart = null
                                progStop = null
                                progTitle = null
                                progDesc = null
                                inTitle = false
                                inDesc = false
                            }
                        }
                    }
                }
            )
        } catch (e: Exception) {
            Log.e("XmltvParser", "SAX parse failed", e)
        }

        out.values.forEach { it.sortBy { e -> e.startTime } }
        return ParseResult(out, channels)
    }

    /**
     * Parses the feed. [day] ("yyyy-MM-dd") restricts entries to that day (local
     * time) when non-null; null returns every entry.
     */
    fun parse(xml: String, day: String? = null): Map<String, List<EpgEntry>> =
        parseAll(xml, day).schedules

    /** Declared `<channel>` elements of the feed. */
    fun parseChannels(xml: String): List<FeedChannel> =
        parseAll(xml).channels

    // --- XMLTV time helpers ---

    private fun matchClassic(v: String): MatchResult? =
        Regex("^(\\d{4})(\\d{2})(\\d{2})(\\d{2})?(\\d{2})?(\\d{2})?\\s*([+-]\\d{2}:?\\d{2})?$")
            .matchEntire(v)

    private fun matchIso(v: String): MatchResult? =
        Regex("^(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2}):(\\d{2})(?:\\.\\d+)?\\s*([+-]\\d{2}:?\\d{2})?$")
            .matchEntire(v)

    /**
     * Parses XMLTV timestamps, returning Unix seconds (UTC).
     *
     * Supported layouts:
     *  - classic: "20250101190000 +0100"
     *  - ISO-ish: "2025-01-01 19:00:00.000 +0100" / "2025-01-01T19:00:00+01:00"
     *
     * When the feed omits an offset, the time is interpreted in the device's
     * local zone (standard XMLTV fallback).
     */
    fun parseXmltvTime(value: String): Long? {
        val v = value.trim()
        if (v.isEmpty()) return null

        val m = matchClassic(v) ?: matchIso(v) ?: return null
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
