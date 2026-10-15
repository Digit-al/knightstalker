// SPDX-License-Identifier: GPL-3.0-or-later
package org.knightstalker.app.data.remote.parser

import android.util.Log
import org.knightstalker.app.domain.model.EpgEntry
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.text.MatchResult

/**
 * Parser for XMLTV EPG files / feeds.
 *
 * Uses Android's native [XmlPullParser] (pull-based, streaming) so that large
 * feeds (tens of MB) can be parsed without loading the entire DOM tree into
 * memory — a 67 MB feed that would otherwise OOM a phone now parses with a
 * flat, small result map.
 *
 * We deliberately avoid `SAXParserFactory` / kxml2 on Android: depending on
 * the API level it can fail with "This parser does not support specification
 * Unknown version 0.0", or silently try to resolve the XMLTV DOCTYPE DTD and
 * return zero entries. `XmlPullParser` (also kxml2, but driven directly) is
 * the reliable, platform-blessed choice.
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
     * Parses the feed in a single streaming pass.
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
        // instead of a silent empty result (critical on Android).
        var parseError: String? = null

        // Strip the DOCTYPE declaration before parsing. The standard XMLTV feed
        // uses `<!DOCTYPE tv SYSTEM "xmltv.dtd">`; some Android XML engines try
        // to resolve that external DTD and choke. Stripping it (through to the
        // <tv> root) leaves the XML well-formed with no DTD reference.
        val cleanXml = xml.replace(
            Regex("<!DOCTYPE[\\s\\S]*?><tv", RegexOption.IGNORE_CASE),
            "<tv"
        )

        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            // XMLTV feeds never use namespace prefixes — element names and
            // attribute names are always returned as-is.
            parser.setInput(StringReader(cleanXml))

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "channel" -> {
                                chanId = parser.getAttributeValue(null, "id")
                            }

                            "display-name" -> {
                                if (chanId != null && !inChanName) {
                                    inChanName = true
                                    chanName = StringBuilder()
                                }
                            }

                            "programme" -> {
                                val ch = parser.getAttributeValue(null, "channel")?.takeIf { it.isNotBlank() }
                                val startStr = parser.getAttributeValue(null, "start")
                                val stopStr = parser.getAttributeValue(null, "stop")
                                val stopDateStr = parser.getAttributeValue(null, "stopdate")
                                val durationStr = parser.getAttributeValue(null, "duration")

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

                    XmlPullParser.TEXT -> {
                        val text = parser.text
                        when {
                            inTitle -> progTitle?.append(text)
                            inDesc -> progDesc?.append(text)
                            inChanName -> chanName?.append(text)
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
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
                event = parser.next()
            }
        } catch (e: Exception) {
            parseError = e.toString()
            Log.e("XmltvParser", "XmlPullParser failed", e)
        }

        // Fallback: if the pull parser returned nothing, run the lightweight
        // string scanner as a last resort — guarantees a result for well-formed
        // XMLTV feeds even on quirky platforms.
        if (out.isEmpty() && channels.isEmpty()) {
            Log.w("XmltvParser", "XmlPullParser produced 0 results — running fallback scanner")
            fallbackScan(cleanXml, dayStart, dayEnd, out, channels)
        }

        out.values.forEach { it.sortBy { e -> e.startTime } }
        return ParseResult(out, channels, parseError)
    }

    /**
     * Lightweight fallback parser that extracts <programme> blocks using
     * string operations (indexOf). Used when the pull parser fails.
     */
    private fun fallbackScan(
        xml: String,
        dayStart: Long?,
        dayEnd: Long?,
        out: MutableMap<String, MutableList<EpgEntry>>,
        channels: MutableList<FeedChannel>
    ) {
        val progStart = "<programme"
        val progEnd = "</programme>"
        val seenChannels = mutableSetOf<String>()
        var pos = 0
        var count = 0

        while (true) {
            val tagStart = xml.indexOf(progStart, pos)
            if (tagStart == -1) break

            // Find the end of the opening tag (the `>` that closes it).
            val tagEnd = xml.indexOf('>', tagStart)
            if (tagEnd == -1) break

            val tagXml = xml.substring(tagStart, tagEnd + 1)

            // Find the closing </programme>.
            val blockEnd = xml.indexOf(progEnd, tagEnd)
            if (blockEnd == -1) break
            val blockEndFull = blockEnd + progEnd.length
            val blockXml = xml.substring(tagEnd + 1, blockEnd)

            // Extract channel attribute.
            val channel = extractAttr(tagXml, "channel")
            val startStr = extractAttr(tagXml, "start")
            val stopStr = extractAttr(tagXml, "stop")

            // Parse times.
            val start = startStr?.let { try { parseXmltvTime(it) } catch (_: Exception) { null } }
            val stop = stopStr?.let { try { parseXmltvTime(it) } catch (_: Exception) { null } }
                ?: (start?.plus(3600) ?: null)

            // Extract title (first <title...>...</title> in the block).
            val title = extractTagText(blockXml, "title") ?: "Programme"

            // Day filter.
            if (channel != null && start != null && stop != null) {
                val inDay = dayStart == null || dayEnd == null || (stop > dayStart && start < dayEnd)
                if (inDay) {
                    val entry = EpgEntry(
                        id = "${channel}_${start}",
                        channelId = channel,
                        title = title,
                        description = null,
                        startTime = start,
                        endTime = stop,
                        duration = ((stop - start) / 60).toInt()
                    )
                    out.getOrPut(channel) { mutableListOf() }.add(entry)
                    count++
                }
            }

            // Track channel ids so we can populate the channels list.
            if (channel != null && seenChannels.add(channel)) {
                channels.add(FeedChannel(channel, null))
            }

            pos = blockEndFull
        }

        Log.d("XmltvParser", "Fallback scan: $count programmes from ${out.size} channels")
    }

    /**
     * Extracts a single- or double-quoted attribute value from an XML tag string.
     */
    private fun extractAttr(tag: String, name: String): String? {
        // Pattern: name="value" or name='value'
        val patterns = listOf(
            Regex("$name=\"([^\"]*)\""),
            Regex("$name='([^']*)'")
        )
        for (p in patterns) {
            val m = p.find(tag)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    /**
     * Extracts the text content of the first occurrence of a named element.
     * Returns the text between <name...> and </name> (or null if not found).
     */
    private fun extractTagText(block: String, tagName: String): String? {
        val openTag = "<${tagName}"
        var idx = block.indexOf(openTag)
        while (idx != -1) {
            // Skip to end of opening tag.
            val gt = block.indexOf('>', idx)
            if (gt == -1) break
            // Self-closing?
            if (gt >= 1 && block[gt - 1] == '/') {
                idx = block.indexOf(openTag, gt)
                continue
            }
            val closeTag = "</${tagName}>"
            val endIdx = block.indexOf(closeTag, gt)
            if (endIdx == -1) break
            return block.substring(gt + 1, endIdx).trim()
        }
        return null
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
     * Converts an XMLTV wall-clock time to epoch SECONDS.
     *
     * When the feed specifies a UTC offset (the classic `+0200`), the
     * wall-clock is interpreted AT THAT offset — independent of the device's
     * time zone. When the feed omits the offset, the time is interpreted in the
     * device's local zone (the standard XMLTV fallback).
     *
     * Implemented with `java.util.GregorianCalendar` only — `java.time` is not
     * available natively below API 26 and would crash the parser (silently
     * yielding 0 entries) on API 24/25 without core-library desugaring.
     */
    private fun toEpochSeconds(
        year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int,
        offset: Long?
    ): Long {
        return if (offset != null) {
            // Wall clock is expressed at `offset` (e.g. +0200): build the epoch
            // as if UTC, then subtract the offset.
            val utc = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC"), java.util.Locale.US)
            utc.clear()
            utc.set(year, month - 1, day, hour, minute, second)
            utc.timeInMillis / 1000L - offset
        } else {
            // No offset in the feed: interpret in the device's local zone
            // (GregorianCalendar in the default TZ handles DST correctly).
            val cal = java.util.GregorianCalendar(java.util.Locale.US)
            cal.clear()
            cal.set(year, month - 1, day, hour, minute, second)
            cal.timeInMillis / 1000L
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
