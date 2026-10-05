package com.nuvio.tv.core.iptv.epg

/**
 * F14 — the ordered list of whole-guide sources one playlist ingests, first = highest priority.
 *
 * Before: ONE source, and an explicit EPG URL *replaced* the provider's own guide (a user who added
 * a better third-party EPG for a few channels lost the panel's guide for all the rest), and only the
 * first URL of an M3U `url-tvg="a.xml,b.xml"` list was ever read.
 *
 * Now: every URL the user typed (one per line or comma separated — the `url-tvg` convention), then
 * the provider's own guide (derived Xtream `xmltv.php`), then every `url-tvg` URL. Each channel is
 * assigned from the FIRST source that can match it ([GuideChannelMatcher]); later sources only
 * fill the channels earlier ones missed, and are not downloaded at all once nothing is left to fill.
 *
 * The `epg_url` column stays one text field (synced as today) holding the list, so no schema change.
 * Pure; identical rules in the KMP twin (NuvioMobile features/iptv/epg).
 */
object EpgSourcePlan {

    /** More than this is a misconfiguration, not a plan — every source is a full guide download. */
    const val MAX_SOURCES = 5

    fun plan(explicit: String?, derivedXtream: String?, urlTvg: String?): List<EpgSource> {
        val out = ArrayList<EpgSource>()
        val seen = HashSet<String>()
        fun add(url: String, kind: EpgSourceKind) {
            if (out.size >= MAX_SOURCES) return
            if (seen.add(url.trim().lowercase())) out.add(EpgSource(url.trim(), kind))
        }
        splitUrls(explicit).forEach { add(it, EpgSourceKind.EXPLICIT) }
        derivedXtream?.takeIf { it.isNotBlank() }?.let { add(it, EpgSourceKind.XTREAM_DERIVED) }
        splitUrls(urlTvg).forEach { add(it, EpgSourceKind.URL_TVG) }
        return out
    }

    /**
     * Splits a typed EPG field / `url-tvg` header into URLs. Separators: newline, whitespace, and a
     * comma that starts a new `http(s)://` URL — a comma INSIDE a URL's query string survives.
     */
    fun splitUrls(text: String?): List<String> {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        val parts = ArrayList<String>()
        for (chunk in raw.split('\n', '\r', ' ', '\t')) {
            val c = chunk.trim()
            if (c.isEmpty()) continue
            var rest = c
            while (true) {
                val cut = nextUrlComma(rest)
                if (cut < 0) { parts.add(rest); break }
                parts.add(rest.substring(0, cut))
                rest = rest.substring(cut + 1).trimStart()
            }
        }
        return parts.map { it.trim().trimEnd(',') }.filter { it.isNotEmpty() }
    }

    private fun nextUrlComma(s: String): Int {
        var from = 0
        while (true) {
            val i = s.indexOf(',', from)
            if (i < 0) return -1
            val after = s.substring(i + 1).trimStart()
            if (after.startsWith("http://", ignoreCase = true) || after.startsWith("https://", ignoreCase = true) ||
                after.startsWith("file://", ignoreCase = true)) return i
            from = i + 1
        }
    }

    /**
     * The key a source's programmes are stored under. Source 0 keeps the bare normalized id — the
     * shape every existing reader and every pre-F14 row uses; later sources are prefixed so two
     * feeds that both call a channel `bbc1.uk` can never interleave rows in one row-set.
     */
    fun storedKey(sourceIndex: Int, normalizedGuideId: String): String =
        if (sourceIndex == 0) normalizedGuideId else "s$sourceIndex:$normalizedGuideId"
}

/** Where a guide source came from (logging, failover: only the derived panel guide walks backups). */
enum class EpgSourceKind { EXPLICIT, URL_TVG, XTREAM_DERIVED }

/** A resolved EPG source: the URL plus where it came from. */
data class EpgSource(val url: String, val kind: EpgSourceKind)

/**
 * Normalizes a channel id (M3U tvg-id, Xtream epg_channel_id or XMLTV channel id) into a match key:
 * lowercase, trimmed, inner whitespace removed — byte-identical to the KMP twin's
 * `normalizeChannelId`. TV used to only lowercase, so `"Sky Sports HD"` vs `"SkySportsHD"` matched on
 * the phone and not here (B10 research, finding 5).
 */
fun normalizeChannelId(id: String): String {
    val sb = StringBuilder(id.length)
    for (c in id.trim()) if (!c.isWhitespace()) sb.append(c.lowercaseChar())
    return sb.toString()
}
