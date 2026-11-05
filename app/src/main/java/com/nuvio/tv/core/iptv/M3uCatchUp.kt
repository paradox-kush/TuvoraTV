package com.nuvio.tv.core.iptv

/*
 * Adapted from homelessbrian/NuvioPlusIPTV (GPL-3.0), livetv/data/CatchupUrlBuilder.kt.
 * Changes: pure and clock-free (the caller passes `nowMs` and an explicit wall-clock `offsetMs`
 * instead of reading the device zone), a typed style enum, and the Xtream style delegates to
 * [XtreamCatchUp] so there is one definition of the timeshift URL shapes.
 */

import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The catch-up dialect a playlist asks for with `catchup="..."` / `catchup-type="..."`. */
enum class M3uCatchUpType {
    /** `catchup-source` is a template: absolute (replaces the live URL) or relative (appended). */
    DEFAULT,

    /** `catchup-source` is always appended to the live URL. */
    APPEND,

    /** `?utc=<start>&lutc=<now>` on the live URL (`shift` / `timeshift`). */
    SHIFT,

    /** Flussonic archive URLs (`flussonic`, `flussonic-hls`, `flussonic-ts`, `fs`). */
    FLUSSONIC,

    /** Xtream `/timeshift/user/pass/minutes/start/id.ext` (`xc`, `xtream`). */
    XTREAM,
}

/** Catch-up settings of one channel: [days] is the archive depth (at least 1). */
data class M3uCatchUpInfo(val type: M3uCatchUpType, val source: String?, val days: Int)

/** Pure catch-up URL building for M3U playlists. */
object M3uCatchUp {

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * Maps a `catchup` / `catchup-type` attribute to a style. No value means the playlist default
     * ([M3uCatchUpType.DEFAULT]). An unrecognised value is treated as `default` when a
     * [source] template exists (the template is all there is to go on) and as unusable (null)
     * otherwise.
     */
    fun catchUpType(value: String?, source: String?): M3uCatchUpType? {
        val v = value?.trim()?.lowercase().orEmpty()
        return when (v) {
            "" , "default" -> M3uCatchUpType.DEFAULT
            "append" -> M3uCatchUpType.APPEND
            "shift", "timeshift" -> M3uCatchUpType.SHIFT
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> M3uCatchUpType.FLUSSONIC
            "xc", "xtream" -> M3uCatchUpType.XTREAM
            else -> if (source.isNullOrBlank()) null else M3uCatchUpType.DEFAULT
        }
    }

    /** True when [programStartMs] has started and is still inside the channel's archive depth. */
    fun isAvailable(info: M3uCatchUpInfo?, programStartMs: Long, nowMs: Long): Boolean {
        if (info == null) return false
        if (programStartMs >= nowMs) return false
        return nowMs - programStartMs <= info.days.coerceAtLeast(1) * DAY_MS
    }

    /**
     * The replay URL for [startMs, endMs) on the channel whose live URL is [liveUrl], or null when
     * the style cannot be built for it (missing template, URL of the wrong shape).
     *
     * [offsetMs] is the wall-clock offset from UTC used for the bare local-time placeholders
     * (`{Y}{m}{d}{H}{M}{S}`) and for the Xtream `start` stamp; `{utc:...}`-style formatted
     * placeholders are always UTC. The replay is at least one minute long.
     */
    fun build(
        info: M3uCatchUpInfo,
        liveUrl: String,
        startMs: Long,
        endMs: Long,
        nowMs: Long,
        offsetMs: Long,
    ): String? {
        val start = startMs / 1000
        val end = endMs / 1000
        val now = nowMs / 1000
        val duration = (end - start).coerceAtLeast(60)
        val src = info.source
        // A Xtream-looking link on a playlist that does not say which dialect: TiviMate assumes
        // Xtream's /timeshift/ links, so do we, and plain `?utc=` is the last resort.
        val guess = { xtream(liveUrl, startMs, duration, offsetMs) ?: shift(liveUrl, start, now) }
        return when (info.type) {
            M3uCatchUpType.APPEND -> src?.let { liveUrl + fill(it, start, end, now, duration, offsetMs) }
            M3uCatchUpType.DEFAULT -> when {
                src.isNullOrBlank() -> guess()
                src.startsWith("http", ignoreCase = true) -> fill(src, start, end, now, duration, offsetMs)
                else -> liveUrl + fill(src, start, end, now, duration, offsetMs)
            }
            M3uCatchUpType.SHIFT -> if (src.isNullOrBlank()) guess() else shift(liveUrl, start, now)
            M3uCatchUpType.FLUSSONIC -> flussonic(liveUrl, start, duration)
            M3uCatchUpType.XTREAM -> xtream(liveUrl, startMs, duration, offsetMs)
        }
    }

    private fun shift(url: String, start: Long, now: Long): String {
        val sep = if (url.contains('?')) '&' else '?'
        return "$url${sep}utc=$start&lutc=$now"
    }

    private val FLUSSONIC_URL = Regex("""^(https?://[^/]+)/(.*)/([^/?]*)(\?.*)?$""")

    private fun flussonic(url: String, start: Long, duration: Long): String? {
        val m = FLUSSONIC_URL.find(url) ?: return null
        val host = m.groupValues[1]
        val stream = m.groupValues[2]
        val file = m.groupValues[3]
        val query = m.groupValues[4]
        return if (file.endsWith(".m3u8") || file.isEmpty()) {
            val base = file.removeSuffix(".m3u8").ifEmpty { "index" }
            "$host/$stream/$base-$start-$duration.m3u8$query"
        } else {
            "$host/$stream/timeshift_abs-$start.ts$query"
        }
    }

    // http://host:port/(live/)?user/pass/id(.ext)?query
    private val XTREAM_LIVE = Regex(
        """^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(\.[a-z0-9]+)?(\?.*)?$""",
        RegexOption.IGNORE_CASE,
    )

    private fun xtream(url: String, startMs: Long, durationSec: Long, offsetMs: Long): String? {
        val m = XTREAM_LIVE.find(url) ?: return null
        val id = m.groupValues[4].toIntOrNull() ?: return null
        val ext = m.groupValues[5].removePrefix(".").ifEmpty { null }
        val query = m.groupValues[6]
        val first = XtreamCatchUp.candidateUrls(
            baseUrl = m.groupValues[1],
            username = m.groupValues[2],
            password = m.groupValues[3],
            streamId = id,
            startMs = startMs,
            endMs = startMs + durationSec * 1000,
            containerExtension = ext,
            serverOffsetMs = offsetMs,
        ).firstOrNull() ?: return null
        return first + query
    }

    // --- alternatives -------------------------------------------------------------------------

    private val TIMESHIFT_PATH = Regex(
        """/timeshift/[^/]+/[^/]+/(\d+)/([0-9-]+:[0-9-]+)/\d+""",
        RegexOption.IGNORE_CASE,
    )
    private val TIMESHIFT_URL = Regex(
        """^(https?://[^/]+)/timeshift/([^/]+)/([^/]+)/(\d+)/([0-9-]+:[0-9-]+)/(\d+)(\.[a-z0-9]+)?$""",
        RegexOption.IGNORE_CASE,
    )
    private const val STAMP_PATTERN = "yyyy-MM-dd:HH-mm"

    private fun stampFormat() = SimpleDateFormat(STAMP_PATTERN, Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }

    /**
     * Other ways to ask the same Xtream panel for the same replay, tried in turn when [url] (as
     * built by [build]) fails, because panels differ. Order: the other container (TS instead of
     * HLS or the reverse), the part that has aired so far ([airedMinutes], show still on air),
     * just the show without the tail ([showMinutes]), the `timeshift.php` form (from
     * [XtreamCatchUp.candidateUrls]) and finally the `start` written in the other clock.
     *
     * [offsetMs] is the offset [url]'s stamp was written with; [otherOffsetMs] the other clock
     * to try (default UTC when [offsetMs] is not already UTC, else none). A URL that is not an
     * Xtream timeshift link only has the TS fallback of an HLS link.
     */
    fun alternatives(
        url: String,
        offsetMs: Long = 0L,
        otherOffsetMs: Long? = if (offsetMs != 0L) 0L else null,
        showMinutes: Long? = null,
        airedMinutes: Long? = null,
    ): List<String> {
        val path = url.substringBefore('?')
        val query = url.substring(path.length)
        val m = TIMESHIFT_URL.find(path) ?: return listOfNotNull(tsFallback(url))
        val host = m.groupValues[1]
        val user = m.groupValues[2]
        val pass = m.groupValues[3]
        val minutes = m.groupValues[4]
        val stamp = m.groupValues[5]
        val id = m.groupValues[6]
        val ext = m.groupValues[7]
        val isHls = ext.equals(".m3u8", true)
        val other = if (isHls) ".ts" else ".m3u8"
        val same = ext.ifEmpty { ".ts" }
        val total = minutes.toLongOrNull() ?: 0
        val out = LinkedHashSet<String>()
        fun form(min: Any, st: String, e: String) = "$host/timeshift/$user/$pass/$min/$st/$id$e$query"

        out += form(minutes, stamp, other)
        airedMinutes?.coerceAtLeast(1)?.takeIf { it < total }?.let {
            out += form(it, stamp, same); out += form(it, stamp, other)
        }
        showMinutes?.coerceAtLeast(1)?.takeIf { it < total }?.let {
            out += form(it, stamp, same); out += form(it, stamp, other)
        }
        val startUtcMs = runCatching { stampFormat().parse(stamp)?.time }.getOrNull()?.minus(offsetMs)
        if (startUtcMs != null && id.toIntOrNull() != null && total > 0) {
            // The timeshift.php form comes from XtreamCatchUp, so credentials are decoded first.
            XtreamCatchUp.candidateUrls(
                baseUrl = host, username = decode(user), password = decode(pass),
                streamId = id.toInt(), startMs = startUtcMs, endMs = startUtcMs + total * 60_000L,
                containerExtension = null, serverOffsetMs = offsetMs,
            ).firstOrNull { it.contains("timeshift.php") && !it.contains("extension=") }
                ?.let { out += it }
            if (otherOffsetMs != null) {
                val otherStamp = stampFormat().format(Date(startUtcMs + otherOffsetMs))
                if (otherStamp != stamp) out += form(minutes, otherStamp, same)
            }
        }
        out.remove(url)
        return out.toList()
    }

    private fun decode(s: String) = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    /** The TS version of an Xtream HLS catch-up link, for panels that don't offer HLS. */
    fun tsFallback(url: String): String? {
        val path = url.substringBefore('?')
        if (!path.contains("/timeshift/") || !path.endsWith(".m3u8", ignoreCase = true)) return null
        return path.dropLast(".m3u8".length) + ".ts" + url.substring(path.length)
    }

    /**
     * Xtream replay links name a whole minute (9:10, never 9:10:45): the replay really starts at
     * the start of that minute.
     */
    fun wholeMinutes(url: String): Boolean {
        val path = url.substringBefore('?')
        return TIMESHIFT_PATH.containsMatchIn(path) || path.endsWith("timeshift.php", ignoreCase = true)
    }

    // --- link form ----------------------------------------------------------------------------

    /**
     * Which kind of link [url] is next to the first link tried ([primary]): "php", or "hls" /
     * "ts", plus "+clock" when the stamp is written in the other clock and "+short" when it asks
     * for a shorter window. Remembered per provider so the next replay goes straight to the
     * kind that worked.
     */
    fun linkForm(url: String, primary: String): String {
        val path = url.substringBefore('?')
        if (path.endsWith("timeshift.php", ignoreCase = true)) return "php"
        val ext = if (path.endsWith(".m3u8", ignoreCase = true)) "hls" else "ts"
        val m = TIMESHIFT_PATH.find(path)
        val pm = TIMESHIFT_PATH.find(primary.substringBefore('?'))
        val clock = if (m != null && pm != null && m.groupValues[2] != pm.groupValues[2]) "+clock" else ""
        val short = if (m != null && pm != null &&
            (m.groupValues[1].toLongOrNull() ?: 0) < (pm.groupValues[1].toLongOrNull() ?: 0)
        ) "+short" else ""
        return ext + clock + short
    }

    /**
     * [primary] and its [alternatives] in the order to try them: the kind that worked last time
     * on this provider ([form], from [linkForm]) first.
     */
    fun preferForm(primary: String, alternatives: List<String>, form: String?): List<String> {
        val all = listOf(primary) + alternatives
        if (form == null) return all
        // HLS was switched off for this provider (it failed there): don't go back to it.
        if (form.startsWith("hls") && !primary.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) return all
        val pick = all.firstOrNull { linkForm(it, primary) == form }
            ?: all.firstOrNull { linkForm(it, primary) == form.replace("+short", "") }
            ?: return all
        return listOf(pick) + all.filter { it != pick }
    }

    // --- placeholders -------------------------------------------------------------------------


    /**
     * Fills the template. Seconds-based values are epoch seconds. `{utc}`/`{start}` are the start,
     * `{utcend}`/`{end}` the end, `{lutc}`/`{now}`/`{timestamp}` the present, `{duration}` the
     * length and `{offset}` the age of the start; `{duration:N}`/`{offset:N}` divide by N.
     * `{utc:YmdHMS}`-style forms format that instant in UTC. Bare `{Y}{m}{d}{H}{M}{S}` are the
     * start's wall-clock fields at [offsetMs] from UTC. A leading `$` is optional throughout.
     */
    internal fun fill(template: String, start: Long, end: Long, now: Long, duration: Long, offsetMs: Long): String {
        val local = start * 1000 + offsetMs
        // One pass over the template: a value produced by one placeholder is never scanned for another.
        return PLACEHOLDER.replace(template) { m ->
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> {
                    val epoch = when (g[1]) {
                        "utcend", "end" -> end
                        "lutc", "now", "timestamp" -> now
                        else -> start
                    }
                    formatPattern(g[2], epoch * 1000)
                }
                g[3].isNotEmpty() -> (duration / (g[3].toLongOrNull()?.coerceAtLeast(1) ?: 1)).toString()
                g[4].isNotEmpty() -> ((now - start) / (g[4].toLongOrNull()?.coerceAtLeast(1) ?: 1)).toString()
                g[5].isNotEmpty() -> when (g[5]) {
                    "utc", "start" -> start
                    "timestamp", "lutc", "now" -> now
                    "utcend", "end" -> end
                    "duration" -> duration
                    else -> now - start // offset
                }.toString()
                else -> formatPattern(g[6], local)
            }
        }
    }

    private val PLACEHOLDER = Regex(
        """\$?\{(?:(utc|start|utcend|end|lutc|now|timestamp):([^}]+)|duration:(\d+)|offset:(\d+)|(utc|start|timestamp|utcend|end|lutc|now|duration|offset)|([YmdHMS]))\}""",
    )

    /** `Y m d H M S` pattern letters to a UTC-formatted instant; other letters stay literal. */
    private fun formatPattern(pattern: String, epochMs: Long): String {
        val java = buildString {
            pattern.forEach { c ->
                append(
                    when (c) {
                        'Y' -> "yyyy"; 'm' -> "MM"; 'd' -> "dd"
                        'H' -> "HH"; 'M' -> "mm"; 'S' -> "ss"
                        '\'' -> "''"
                        else -> if (c.isLetter()) "'$c'" else c.toString()
                    },
                )
            }
        }
        return runCatching {
            SimpleDateFormat(java, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(epochMs))
        }.getOrDefault((epochMs / 1000).toString())
    }
}
