package com.nuvio.tv.core.iptv.content

// Catch-up attribute parsing adapted from homelessbrian/NuvioPlusIPTV (GPL-3.0), livetv/data/M3uParser.kt.
import com.nuvio.tv.core.iptv.M3uCatchUp
import com.nuvio.tv.core.iptv.M3uCatchUpInfo

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
    val ext: String?,          // container extension from the URL tail (mp4/mkv/ts/...), null if none
    val catchUp: M3uCatchUpInfo? = null, // catch-up attributes (entry over #EXTM3U header default), null = none
    val headers: M3uChannelHeaders? = null, // per-channel User-Agent / Referer / Origin, null = none
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
    fun parseExtInf(
        extInfLine: String,
        url: String,
        headerCatchUp: Map<String, String> = emptyMap(),
        extGrp: String? = null,
        directiveHeaders: Map<String, String> = emptyMap(),
    ): M3UEntry? {
        if (!extInfLine.startsWith("#EXTINF")) return null
        // Display name = everything after the FIRST comma outside a quoted attribute: the name itself may
        // hold commas ("BP Comma, Name"), an attribute value's commas are inside quotes.
        val comma = firstUnquotedComma(extInfLine)
        val displayName = if (comma >= 0) extInfLine.substring(comma + 1).trim() else ""
        val attrsPart = if (comma >= 0) extInfLine.substring(0, comma) else extInfLine
        val attrs = HashMap<String, String>()
        for (m in ATTR.findAll(attrsPart)) attrs[m.groupValues[1].lowercase()] = m.groupValues[2]

        val tvgName = attrs["tvg-name"]?.takeIf { it.isNotBlank() }
        val name = displayName.ifBlank { tvgName.orEmpty() }
        val ext = extOf(url)
        val kind = classify(url, ext)
        return M3UEntry(
            name = name,
            tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
            tvgName = tvgName,
            logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() && it != "https://image.tmdb.org/t/p/w600_and_h900_bestv2" },
            group = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: extGrp,
            url = url,
            kind = kind,
            ext = ext,
            // Catch-up is a live-channel feature: header defaults must not leak onto VOD/series rows.
            catchUp = if (kind == M3UKind.LIVE) {
                catchUpOf(headerCatchUp + attrs.filterKeys { it in CATCHUP_KEYS && !attrs[it].isNullOrBlank() })
            } else null,
            // #EXTVLCOPT / #EXTHTTP lines are more specific than the attribute shorthand, so they win.
            headers = channelHeadersOf(attributeHeadersOf(attrs) + directiveHeaders),
        )
    }

    /** Canonical header keys this parser keeps (lower case). Cookie is deliberately absent. */
    private const val UA = "user-agent"
    private const val REFERER = "referer"
    private const val ORIGIN = "origin"
    private const val MAX_HEADER_VALUE = 512

    /** `http-user-agent="..."` / `http-referrer="..."` (and their spellings) on the #EXTINF line. */
    private fun attributeHeadersOf(attrs: Map<String, String>): Map<String, String> = buildMap {
        (attrs["http-user-agent"] ?: attrs["user-agent"])?.let { put(UA, it) }
        (attrs["http-referrer"] ?: attrs["http-referer"] ?: attrs["referer"] ?: attrs["referrer"])?.let { put(REFERER, it) }
        attrs["http-origin"]?.let { put(ORIGIN, it) }
    }

    /** One `#EXTVLCOPT:http-user-agent=...` line → (canonical key, value), or null for any other option. */
    internal fun vlcOptHeaderOf(line: String): Pair<String, String>? {
        val body = line.substring(line.indexOf(':') + 1).trim()
        val eq = body.indexOf('=')
        if (eq <= 0) return null
        val key = when (body.substring(0, eq).trim().lowercase()) {
            "http-user-agent" -> UA
            "http-referrer", "http-referer" -> REFERER
            "http-origin" -> ORIGIN
            else -> return null
        }
        return key to body.substring(eq + 1)
    }

    /** `#EXTHTTP:{json}` → the allow-listed headers in it; anything malformed yields nothing. */
    internal fun extHttpHeadersOf(line: String): Map<String, String> {
        val json = line.substring(line.indexOf(':') + 1).trim()
        val obj = try {
            kotlinx.serialization.json.Json.parseToJsonElement(json) as? kotlinx.serialization.json.JsonObject
        } catch (_: Exception) {
            null
        } ?: return emptyMap()
        val out = HashMap<String, String>()
        for ((name, value) in obj) {
            val text = (value as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            when (name.trim().lowercase()) {
                UA -> out[UA] = text
                "referer", "referrer" -> out[REFERER] = text
                ORIGIN -> out[ORIGIN] = text
            }
        }
        return out
    }

    /**
     * The channel's headers from canonical keys, or null when none is usable. A value is dropped if blank,
     * over [MAX_HEADER_VALUE] chars, or carrying a line break (header injection).
     */
    internal fun channelHeadersOf(raw: Map<String, String>): M3uChannelHeaders? {
        fun clean(key: String): String? = raw[key]?.trim()?.takeIf {
            it.isNotEmpty() && it.length <= MAX_HEADER_VALUE && it.none { ch -> ch == '\r' || ch == '\n' || ch == '\u0000' }
        }
        val headers = M3uChannelHeaders(userAgent = clean(UA), referer = clean(REFERER), origin = clean(ORIGIN))
        return headers.takeIf { it.userAgent != null || it.referer != null || it.origin != null }
    }

    /** Attribute keys that carry catch-up settings, on an entry or as `#EXTM3U` header defaults. */
    private val CATCHUP_KEYS = setOf("catchup", "catchup-type", "catchup-source", "catchup-days", "tvg-rec", "timeshift")

    /**
     * Catch-up attributes found on the `#EXTM3U` line (TiviMate lets a playlist set them once for
     * every channel); an entry's own attributes override them key by key.
     */
    fun headerCatchUpOf(extM3ULine: String): Map<String, String> {
        if (!extM3ULine.startsWith("#EXTM3U")) return emptyMap()
        val out = HashMap<String, String>()
        for (m in ATTR.findAll(extM3ULine)) {
            val k = m.groupValues[1].lowercase()
            if (k in CATCHUP_KEYS && m.groupValues[2].isNotBlank()) out[k] = m.groupValues[2]
        }
        return out
    }

    /**
     * The catch-up settings in [attrs], or null when the entry has none: it needs a type, a source
     * template or a positive day count. Type defaults to `default`, days to 1.
     */
    internal fun catchUpOf(attrs: Map<String, String>): M3uCatchUpInfo? {
        val typeValue = (attrs["catchup"] ?: attrs["catchup-type"])?.takeIf { it.isNotBlank() }
        val source = attrs["catchup-source"]?.takeIf { it.isNotBlank() }
        val rawDays = (attrs["catchup-days"] ?: attrs["tvg-rec"] ?: attrs["timeshift"])?.trim()?.toIntOrNull()
        if (typeValue == null && source == null && (rawDays ?: 0) <= 0) return null
        val type = M3uCatchUp.catchUpType(typeValue, source) ?: return null
        return M3uCatchUpInfo(type, source, rawDays?.takeIf { it > 0 } ?: 1)
    }

    /** Index of the comma that starts the display name, ignoring commas inside `attr="..."`; -1 if none. */
    private fun firstUnquotedComma(line: String): Int {
        var inQuote = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuote = !inQuote
            else if (c == ',' && !inQuote) return i
        }
        return -1
    }

    /**
     * Streaming parse: walk [reader] ONCE (never materializing the whole body — critical for a
     * 192MB M3U) reading each `#EXTINF` line then its following URL line, emitting one [M3UEntry]
     * per pair via [onEntry]. Non-EXTINF `#` directives (#EXTM3U, #EXTGRP) and blanks are skipped;
     * a stray URL with no preceding #EXTINF is ignored. The `#EXTM3U` header's `url-tvg` /
     * `x-tvg-url` attribute (the default XMLTV EPG location) is surfaced once via [onHeaderTvgUrl]
     * if present. Pure (no Android/DB) so the parse is unit-testable against any Reader.
     */
    fun parseStream(
        reader: java.io.BufferedReader,
        onHeaderTvgUrl: (String) -> Unit = {},
        onEntry: (M3UEntry) -> Unit
    ) {
        var pendingExtInf: String? = null
        var pendingExtGrp: String? = null // B135: `#EXTGRP:` folder for the next entry only; group-title wins
        var headerSeen = false
        var headerCatchUp: Map<String, String> = emptyMap()
        // #EXTVLCOPT / #EXTHTTP between one URL and the next belong to the next entry, before or after its #EXTINF.
        val pendingHeaders = HashMap<String, String>()
        var line = reader.readLine()
        while (line != null) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#EXTINF") -> pendingExtInf = trimmed
                !headerSeen && trimmed.startsWith("#EXTM3U") -> {
                    headerSeen = true
                    tvgUrlOf(trimmed)?.let(onHeaderTvgUrl)
                    headerCatchUp = headerCatchUpOf(trimmed)
                }
                trimmed.startsWith("#EXTGRP:", ignoreCase = true) ->
                    pendingExtGrp = trimmed.substring("#EXTGRP:".length).trim().ifBlank { null }
                trimmed.startsWith("#EXTVLCOPT:", ignoreCase = true) ->
                    vlcOptHeaderOf(trimmed)?.let { (k, v) -> pendingHeaders[k] = v }
                trimmed.startsWith("#EXTHTTP:", ignoreCase = true) -> pendingHeaders.putAll(extHttpHeadersOf(trimmed))
                trimmed.startsWith("#") || trimmed.isEmpty() -> { /* directive / blank */ }
                pendingExtInf != null -> {
                    val entry = parseExtInf(pendingExtInf, trimmed, headerCatchUp, pendingExtGrp, pendingHeaders.toMap())
                    pendingExtInf = null
                    pendingExtGrp = null
                    pendingHeaders.clear()
                    if (entry != null && entry.name.isNotBlank()) onEntry(entry)
                }
                else -> pendingHeaders.clear() // stray URL with no #EXTINF: its options die with it
            }
            line = reader.readLine()
        }
    }

    /**
     * The default XMLTV EPG URL declared on the `#EXTM3U` header line, if any. Providers use either
     * `url-tvg="..."` or `x-tvg-url="..."` (both accepted; first non-blank wins). Returns null when
     * neither is present. Used to resolve an EPG source when the account has no explicit epgUrl.
     */
    fun tvgUrlOf(extM3ULine: String): String? {
        if (!extM3ULine.startsWith("#EXTM3U")) return null
        val attrs = HashMap<String, String>()
        for (m in ATTR.findAll(extM3ULine)) attrs[m.groupValues[1].lowercase()] = m.groupValues[2]
        return (attrs["url-tvg"] ?: attrs["x-tvg-url"])?.takeIf { it.isNotBlank() }
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
