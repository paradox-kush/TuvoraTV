package com.nuvio.tv.core.iptv.identity

/**
 * B64 / D2 — the ONE series grouping every platform uses for an M3U playlist, so a series id (and the
 * episode under it) is the same on TV, phone, tablet and desktop.
 *
 *  - an entry under a `/series/` path is an episode of [seriesKeyOf] its name;
 *  - a `/movie/` (VOD) entry whose name reads as an episode — "Show S01E02", "Show 1x02" — with a
 *    non-blank show name before the marker is PROMOTED to an episode of that show ([promotion]; TV's
 *    long-standing behaviour, owner decision D2 2026-10-03: ported to the phone/desktop first, then
 *    shared). A bare "S01E02" (no show name) stays a movie;
 *  - the series id is [M3uIdentity.sidOf]("series:" + key) — the key is the display name with the
 *    SxxExx marker, bracketed tags and quality tags removed, lowercased (not scoped by category: the
 *    same show in two groups is one series).
 *
 * Pure; twins: NuvioMobile/NuvioDesktop `features/iptv/identity/M3uSeriesGrouping.kt` (same tests).
 */
object M3uSeriesGrouping {

    data class Promotion(val seriesKey: String, val seriesName: String, val season: Int, val episode: Int)

    // "Show Name S01 E02" / "Show Name S01E02" / "Show Name 1x02" -> (1, 2)
    private val seasonEpisodeRegex = Regex("""[sS](\d{1,3})\s*[eExX]\s*(\d{1,4})""")
    private val altSeasonEpisodeRegex = Regex("""(?<![\dsS])(\d{1,2})[xX](\d{1,3})(?!\d)""")
    private val bracketed = Regex("""[\[(].*?[\])]""")
    private val qualityTag = Regex("""\b(FHD|HD|SD|4K|UHD|HEVC|H265|H264)\b""", RegexOption.IGNORE_CASE)
    private val spaces = Regex("""\s+""")

    fun seasonEpisodeOf(name: String): Pair<Int, Int>? {
        seasonEpisodeRegex.find(name)?.let { m -> return m.groupValues[1].toInt() to m.groupValues[2].toInt() }
        altSeasonEpisodeRegex.find(name)?.let { m -> return m.groupValues[1].toInt() to m.groupValues[2].toInt() }
        return null
    }

    /**
     * The key every episode of one show shares: the display name with SxxExx / NxNN, bracketed tags and
     * quality tags removed, trimmed, lowercased; falls back to tvg-name, then the group.
     */
    fun seriesKeyOf(displayName: String, tvgName: String?, group: String?): String {
        val base = displayName.replace(seasonEpisodeRegex, " ").replace(altSeasonEpisodeRegex, " ")
        val stripped = base
            .replace(bracketed, " ")
            .replace(qualityTag, " ")
            .replace(spaces, " ")
            .trim()
            .trimEnd('-', '·', '|', ':')
            .trim()
        val key = stripped.ifBlank { tvgName?.trim().orEmpty() }.ifBlank { group?.trim().orEmpty() }
        return key.lowercase().ifBlank { displayName.lowercase() }
    }

    /** A VOD row named like an episode ("Show S01E02") → the episode it is; null = a genuine movie. */
    fun promotion(name: String, tvgName: String?, group: String?): Promotion? {
        val match = seasonEpisodeRegex.find(name) ?: altSeasonEpisodeRegex.find(name) ?: return null
        val showName = name.substring(0, match.range.first).trim().trimEnd('-', '.', '_', ' ', ':', '|', '·').trim()
        if (showName.isBlank()) return null
        return Promotion(
            seriesKey = seriesKeyOf(name, tvgName, group),
            seriesName = showName,
            season = match.groupValues[1].toInt(),
            episode = match.groupValues[2].toInt(),
        )
    }

    fun seriesSid(seriesKey: String): Int = M3uIdentity.sidOf("series:$seriesKey")
}
