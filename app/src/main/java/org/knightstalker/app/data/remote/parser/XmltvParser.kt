// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.remote.parser

import android.util.Log
import org.knightstalker.app.domain.model.EpgEntry
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.text.SimpleDateFormat
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
        val channels: List<FeedChannel>,
        val error: String? = null
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

        // Captured so the caller can surface WHY parsing produced no entries
        // instead of a silent empty result (critical on Android, where the
        // SAX parser can fail for reasons that don't surface as exceptions
        // on the JVM).
        var parseError: String? = null

        try {
            // Strip the DOCTYPE declaration — on Android the SAX parser may
            // try to fetch the referenced DTD (xmltv.dtd) from the network
            // and fail silently, causing zero entries to be parsed.
            // The XMLTV DOCTYPE often contains an internal subset with its
            // own `>` characters (e.g. <!ELEMENT tv (...)>), so we must use
            // a regex that matches through to the end of the whole declaration.
            // Strategy: remove from `<!DOCTYPE` up to (not including) the first
            // `><tv` which marks the boundary between DOCTYPE and root element.
            val cleanXml = xml.replace(
                Regex("<!DOCTYPE[\\s\\S]*?><tv", RegexOption.IGNORE_CASE),
                "<tv"
            )

            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = false
            factory.isXIncludeAware = false
            val parser = factory.newSAXParser()

            parser.parse(
                InputSource(cleanXml.byteInputStream()),
                object : DefaultHandler() {

                    override fun startElement(
                        uri: String,
                        localName: String,
                        qName: String,
                        attrs: Attributes
                    ) {
                        // Some JVMs (including Android on certain API levels)
                        // leave localName empty when the parser is not
                        // namespace-aware, putting the element name in qName
                        // instead. XMLTV feeds never use namespace prefixes,
                        // so qName is always the plain element name here.
                        val name = localName.ifBlank { qName }
                        when (name) {
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
                        // Same localName/qName quirk as in startElement.
                        val name = localName.ifBlank { qName }
                        when (name) {
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
                                    val displayName = chanName?.toString()?.trim()?.takeIf { it.isNotBlank() }
                                    channels.add(FeedChannel(id, displayName))
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
            parseError = e.toString()
            Log.e("XmltvParser", "SAX parse failed", e)
        }

        out.values.forEach { it.sortBy { e -> e.startTime } }
        return ParseResult(out, channels, parseError)
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

        // Fast path: classic "20250101190000 +0100" — the dominant XMLTV layout.
        // Avoids any regex or Calendar/ZoneId lookup (this is called once per
        // <programme> start AND stop, so it matters for large feeds).
        if (v.length >= 12 &&
            v[4] != '-' && // not ISO-dash-separated (2025-01-01...)
            (v[4] in '0'..'9') && v[6] in '0'..'9'
        ) {
            val year = v.substring(0, 4).toIntOrNull() ?: return null
            val month = v.substring(4, 6).toIntOrNull() ?: return null
            val day = v.substring(6, 8).toIntOrNull() ?: return null
            if (month in 1..12 && day in 1..31) {
                val hour = if (v.length > 8) v.substring(8, 10).toIntOrNull() ?: 0 else 0
                val minute = if (v.length > 10) v.substring(10, 12).toIntOrNull() ?: 0 else 0
                val second = if (v.length > 12) v.substring(12, 14).toIntOrNull() ?: 0 else 0
                if (hour in 0..23 && minute in 0..59 && second in 0..59) {
                    val rest = v.substring(14).trim()
                    val offset = rest.takeIf { it.isNotEmpty() }?.let { parseOffset(it) }
                    return toEpochSeconds(year, month, day, hour, minute, second, offset) / 1000L
                }
            }
        }

        // Fallback: regex-based ISO parsing.
        val m = matchIso(v) ?: return null
        val g = m.groupValues
        val year = g[1].toIntOrNull() ?: return null
        val month = g[2].toIntOrNull() ?: return null
        val day = g[3].toIntOrNull() ?: return null
        val hour = g[4].toIntOrNull() ?: 0
        val minute = g[5].toIntOrNull() ?: 0
        val second = g[6].toIntOrNull() ?: 0
        val offset = g[7].takeIf { it.isNotEmpty() }?.let { parseOffset(it) }
        return toEpochSeconds(year, month, day, hour, minute, second, offset) / 1000L
    }

    /**
     * Converts an XMLTV wall-clock time to epoch millis.
     *
     * When the feed specifies a UTC offset (the classic `+0200`), the
     * wall-clock is interpreted AT THAT offset — independent of the device's
     * time zone. Interpreting it in the device zone and then subtracting the
     * offset again double-counts the shift: a `19:00 +0200` timestamp would
     * end up at 15:00 UTC on a phone set to France (CEST, +0200) instead of
     * the correct 17:00 UTC, pushing the whole schedule two hours into the
     * past and making day-based filters return nothing.
     *
     * When the feed omits the offset, the time is interpreted in the device's
     * local zone (the standard XMLTV fallback).
     */
    private fun toEpochSeconds(
        year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int,
        offset: Long?
    ): Long {
        val clock = java.time.LocalDateTime.of(year, month, day, hour, minute, second)
        return if (offset != null) {
            java.time.OffsetDateTime.of(clock, java.time.ZoneOffset.ofTotalSeconds(offset.toInt()))
                .toInstant()
                .toEpochMilli()
        } else {
            clock.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
    }

    /**
     * Parses an XMLTV offset like "+0100", "-05:30", or "+01:00" into seconds.
     * Returns null when the string does not look like an offset.
     */
    private fun parseOffset(value: String): Long? {
        val sign = when (value[0]) {
            '-' -> -1L
            '+' -> 1L
            else -> return null
        }
        val digits = value.substring(1).replace(":", "")
        if (digits.length < 2) return null
        val oh = digits.take(2).toIntOrNull() ?: return null
        val om = digits.drop(2).take(2).toIntOrNull() ?: 0
        if (oh > 14) return null // sanity: XMLTV offsets are within ±14h
        return sign * (oh * 3600L + om * 60L)
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
