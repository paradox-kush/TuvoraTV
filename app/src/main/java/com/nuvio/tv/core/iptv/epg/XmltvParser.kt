package com.nuvio.tv.core.iptv.epg

import com.nuvio.tv.core.iptv.content.EpgProgramme
import org.xmlpull.v1.XmlPullParser

/**
 * Pure XMLTV parsing helpers — the time conversion and the streaming `<programme>` walk. Kept free
 * of Android/OkHttp so both are unit-testable against any [XmlPullParser] and any time string.
 *
 * XMLTV shape:
 *   <tv>
 *     <channel id="bbc.uk"><display-name>BBC One</display-name></channel>
 *     <programme start="20260702180000 +0000" stop="20260702190000 +0000" channel="bbc.uk">
 *       <title>News</title><desc>Headlines</desc>
 *     </programme>
 *   </tv>
 *
 * Guides run 50–100MB+, so [parseProgrammes] STREAM-parses (pull, never DOM) and filters to the
 * playlist's channel ids up front — programmes for channels the user doesn't have are dropped
 * without allocation.
 */
object XmltvParser {

    /**
     * Convert an XMLTV time (`YYYYMMDDHHMMSS` optionally followed by a ` ±HHMM` / `±HHMM` offset)
     * to UTC epoch millis. With no offset the time is treated as UTC (the XMLTV spec's fallback).
     * Returns null on anything malformed. Examples:
     *   "20260702180000 +0000" -> 2026-07-02 18:00:00 UTC
     *   "20260702180000 +0200" -> 2026-07-02 16:00:00 UTC (offset subtracted)
     *   "20260702180000"       -> 2026-07-02 18:00:00 UTC (assumed)
     */
    fun parseXmltvTime(raw: String?): Long? {
        val s = raw?.trim() ?: return null
        if (s.length < 14) return null
        val digits = s.substring(0, 14)
        if (!digits.all { it.isDigit() }) return null
        val year = digits.substring(0, 4).toInt()
        val month = digits.substring(4, 6).toInt()
        val day = digits.substring(6, 8).toInt()
        val hour = digits.substring(8, 10).toInt()
        val minute = digits.substring(10, 12).toInt()
        val second = digits.substring(12, 14).toInt()
        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..60) return null

        // Base millis as if the wall-clock were UTC.
        val cal = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC")).apply {
            isLenient = false
            clear()
            set(year, month - 1, day, hour, minute, second)
        }
        val utcAsIfNoOffset = try { cal.timeInMillis } catch (e: Exception) { return null }

        // Optional trailing offset: " +0200" / "+0200" / " -0530". Subtract it to reach true UTC.
        val offsetPart = s.substring(14).trim()
        val offsetMs = parseOffsetMs(offsetPart) ?: 0L
        return utcAsIfNoOffset - offsetMs
    }

    /** `+HHMM` / `-HHMM` (or empty/"Z") -> signed millis. null on garbage. */
    private fun parseOffsetMs(offset: String): Long? {
        if (offset.isEmpty() || offset.equals("Z", ignoreCase = true)) return 0L
        val sign = when (offset[0]) { '+' -> 1; '-' -> -1; else -> return null }
        val body = offset.substring(1)
        if (body.length < 4 || !body.take(4).all { it.isDigit() }) return null
        val hh = body.substring(0, 2).toInt()
        val mm = body.substring(2, 4).toInt()
        return sign * (hh * 3_600_000L + mm * 60_000L)
    }

    /**
     * Stream every `<programme>` whose `channel` is in [channelFilter] (already normalized: trim +
     * lowercase), emitting an [EpgProgramme] (channel_id normalized, UTC start/end, title, desc) via
     * [onProgramme]. Programmes for other channels, and ones with an unparseable/absent start-stop,
     * are skipped. [parser] must be positioned at the document start; it is walked to the end.
     *
     * When [channelFilter] is empty, EVERYTHING is emitted (used only by tests / a playlist with no
     * tvg-ids won't call this). The caller bounds memory by passing the real filter.
     */
    fun parseProgrammes(
        parser: XmlPullParser,
        channelFilter: Set<String>,
        onProgramme: (EpgProgramme) -> Unit
    ) {
        var event = parser.eventType
        // State for the programme currently being read.
        var inProgramme = false
        var keep = false
        var channel = ""
        var startMs = 0L
        var endMs = 0L
        var title: String? = null
        var desc: String? = null
        var currentText: StringBuilder? = null   // non-null while inside <title>/<desc>

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "programme" -> {
                        val ch = parser.getAttributeValue(null, "channel")?.trim()?.lowercase().orEmpty()
                        val start = parseXmltvTime(parser.getAttributeValue(null, "start"))
                        val stop = parseXmltvTime(parser.getAttributeValue(null, "stop"))
                        inProgramme = true
                        // Keep only channels we have AND a valid, ordered time window.
                        keep = ch.isNotEmpty() && (channelFilter.isEmpty() || ch in channelFilter) &&
                            start != null && stop != null && stop > start
                        channel = ch
                        startMs = start ?: 0L
                        endMs = stop ?: 0L
                        title = null; desc = null
                    }
                    "title" -> if (inProgramme && keep && title == null) currentText = StringBuilder()
                    "desc" -> if (inProgramme && keep && desc == null) currentText = StringBuilder()
                }
                XmlPullParser.TEXT -> currentText?.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "title" -> { currentText?.let { title = it.toString().trim() }; currentText = null }
                    "desc" -> { currentText?.let { desc = it.toString().trim() }; currentText = null }
                    "programme" -> {
                        if (keep) {
                            onProgramme(
                                EpgProgramme(
                                    channelId = channel,
                                    startMs = startMs,
                                    endMs = endMs,
                                    title = title?.takeIf { it.isNotEmpty() } ?: "",
                                    desc = desc?.takeIf { it.isNotEmpty() }
                                )
                            )
                        }
                        inProgramme = false; keep = false; currentText = null
                    }
                }
            }
            event = parser.next()
        }
    }
}
