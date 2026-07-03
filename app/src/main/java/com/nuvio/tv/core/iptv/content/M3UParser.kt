package com.nuvio.tv.core.iptv.content

/** What an #EXTINF+URL pair classifies as. */
enum class M3UKind { LIVE, VOD, SERIES }

/** The attributes lifted from one `#EXTINF:-1 ...,Display Name` line + its stream URL. */
data class M3UEntry(
    val name: String,          // the text after the comma (falls back to tvg-name)
    val tvgId: String?,        // tvg-id="" (EPG channel id; used later for XMLTV)
    val tvgName: String?,      // tvg-name=""
    val logo: String?,         // tvg-logo=""
    val group: String?,        // group-title="" = category
    val url: String,
    val kind: M3UKind,
    val ext: String?           // container extension from the URL tail (mp4/mkv/ts/...), null if none
)

/**
 * Pure, allocation-light M3U parsing helpers. The heavy lifting (streaming a 192MB body line by
 * line, DB inserts) lives in [M3UClient]; everything here is a pure function so the classification
 * + attribute-extraction rules are unit-testable with no Android/network.
 *
 * Format:  #EXTINF:-1 tvg-id="" tvg-name="X" tvg-logo="url" group-title="CATEGORY",Display Name
 *          https://host:port/movie/user/pass/385215.mp4
 */
object M3UParser {

    /**
     * Classify a stream by its URL, matching the reality of provider M3Us:
     *  - path segment `/live/`, `/movie/`, `/series/` (Xtream-style get.php output) decides outright
     *  - else the container extension: .ts/.m3u8 -> live, .mp4/.mkv/.avi/... -> vod
     *  - unknown -> live (a bare channel URL with no ext is the common "live" shape)
     *
     * SERIES is only inferable from the `/series/` path here; a `.mp4` under no series hint is a
     * movie. [M3UClient] promotes VOD entries whose display name parses as "S01E02" into series
     * episodes as a second pass, since many providers ship episodes as plain /movie/ .mp4 rows.
     */
    fun classify(url: String, ext: String?): M3UKind {
        val path = pathOf(url).lowercase()
        when {
            path.contains("/series/") -> return M3UKind.SERIES
            path.contains("/movie/") || path.contains("/movies/") -> return M3UKind.VOD
            path.contains("/live/") -> return M3UKind.LIVE
        }
        return when (ext?.lowercase()) {
            "ts", "m3u8" -> M3UKind.LIVE
            "mp4", "mkv", "avi", "mov", "flv", "wmv", "m4v", "mpg", "mpeg", "webm" -> M3UKind.VOD
            else -> M3UKind.LIVE
        }
    }

    /** Container extension from the URL's last path segment (no query), or null. */
    fun extOf(url: String): String? {
        val seg = pathOf(url).substringAfterLast('/', "")
        val dot = seg.lastIndexOf('.')
        if (dot < 0 || dot == seg.length - 1) return null
        val ext = seg.substring(dot + 1)
        // Guard against a dot in a hostless path or a domain-looking tail.
        return ext.takeIf { it.length in 2..5 && it.all { ch -> ch.isLetterOrDigit() } }
    }

    /** Strip scheme+host+query, leaving the path — cheap, no URL library (parses 685k lines). */
    private fun pathOf(url: String): String {
        var s = url
        val scheme = s.indexOf("://")
        if (scheme >= 0) {
            val afterScheme = s.substring(scheme + 3)
            val slash = afterScheme.indexOf('/')
            s = if (slash >= 0) afterScheme.substring(slash) else ""
        }
        val q = s.indexOf('?')
        if (q >= 0) s = s.substring(0, q)
        return s
    }

    private val ATTR = Regex("""([\w-]+)="([^"]*)"""")

    /**
     * Parse one `#EXTINF:...` line (without the trailing URL) into its attributes + display name.
     * Returns null only if the line isn't an EXTINF line. The URL + kind are supplied by the
     * caller (which reads the next line).
     */
    fun parseExtInf(extInfLine: String, url: String): M3UEntry? {
        if (!extInfLine.startsWith("#EXTINF")) return null
        // Display name = everything after the LAST comma that isn't inside a quoted attribute.
        val comma = lastUnquotedComma(extInfLine)
        val displayName = if (comma >= 0) extInfLine.substring(comma + 1).trim() else ""
        val attrsPart = if (comma >= 0) extInfLine.substring(0, comma) else extInfLine
        val attrs = HashMap<String, String>()
        for (m in ATTR.findAll(attrsPart)) attrs[m.groupValues[1].lowercase()] = m.groupValues[2]

        val tvgName = attrs["tvg-name"]?.takeIf { it.isNotBlank() }
        val name = displayName.ifBlank { tvgName.orEmpty() }
        val ext = extOf(url)
        return M3UEntry(
            name = name,
            tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
            tvgName = tvgName,
            logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() && it != "https://image.tmdb.org/t/p/w600_and_h900_bestv2" },
            group = attrs["group-title"]?.takeIf { it.isNotBlank() },
            url = url,
            kind = classify(url, ext),
            ext = ext
        )
    }

    /** Index of the comma that starts the display name, ignoring commas inside `attr="..."`. */
    private fun lastUnquotedComma(line: String): Int {
        var inQuote = false
        var last = -1
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuote = !inQuote
            else if (c == ',' && !inQuote) last = i
        }
        return last
    }

    /**
     * Streaming parse: walk [reader] ONCE (never materializing the whole body — critical for a
     * 192MB M3U) reading each `#EXTINF` line then its following URL line, emitting one [M3UEntry]
     * per pair via [onEntry]. Non-EXTINF `#` directives (#EXTM3U, #EXTGRP) and blanks are skipped;
     * a stray URL with no preceding #EXTINF is ignored. Pure (no Android/DB) so the parse is
     * unit-testable against any Reader (plain or gzip-backed).
     */
    fun parseStream(reader: java.io.BufferedReader, onEntry: (M3UEntry) -> Unit) {
        var pendingExtInf: String? = null
        var line = reader.readLine()
        while (line != null) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#EXTINF") -> pendingExtInf = trimmed
                trimmed.startsWith("#") || trimmed.isEmpty() -> { /* directive / blank */ }
                pendingExtInf != null -> {
                    val entry = parseExtInf(pendingExtInf, trimmed)
                    pendingExtInf = null
                    if (entry != null && entry.name.isNotBlank()) onEntry(entry)
                }
                else -> { /* stray URL with no #EXTINF */ }
            }
            line = reader.readLine()
        }
    }

    private val SXXEXX = Regex("""[sS](\d{1,2})[\s._-]*[eE](\d{1,3})""")
    private val SEASON_X_EP = Regex("""(\d{1,2})x(\d{1,3})""")

    /**
     * If a VOD display name looks like a series episode ("Show S01E02", "Show 1x02"), return the
     * (seriesName, season, episode). Many providers ship episodes as plain /movie/ .mp4 rows, so
     * [M3UClient] re-classifies these into the series lane. null = a genuine movie.
     */
    fun seriesEpisodeOf(name: String): Triple<String, Int, Int>? {
        SXXEXX.find(name)?.let { m ->
            val series = name.substring(0, m.range.first).trim().trimEnd('-', '.', '_', ' ')
            if (series.isNotBlank()) return Triple(series, m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }
        SEASON_X_EP.find(name)?.let { m ->
            val series = name.substring(0, m.range.first).trim().trimEnd('-', '.', '_', ' ')
            if (series.isNotBlank()) return Triple(series, m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }
        return null
    }
}
