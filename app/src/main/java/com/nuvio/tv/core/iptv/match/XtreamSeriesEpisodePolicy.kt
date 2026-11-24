package com.nuvio.tv.core.iptv.match

import com.nuvio.tv.core.iptv.XtreamEpisode

/**
 * Maps a TMDB (show, season, episode) onto the right Xtream series entry + episode, across the two
 * shapes panels use:
 *
 *  - **Whole-series** (the common shape): one `series_id` holds every season, episodes carry their
 *    real `season` number. Legacy behaviour — match internal `season == s && episodeNum == e`.
 *  - **Split-season** (e.g. xsc.loruhon.com): each *season* is its own `series_id` named
 *    "<Show> S<n> <lang>", and inside it every episode is flattened to season 1 with generic
 *    "EP001" titles. The real season lives only in the entry NAME, so we read it from there and
 *    then match episodes by number alone.
 *
 * Pure and side-effect free so the season/episode arithmetic is unit-tested without a panel; the
 * network fetch (get_series_info) stays in [XtreamStreamSource]. Twin of NuvioMobile's policy.
 */
object XtreamSeriesEpisodePolicy {

    // "<Show> S5 En" / "... Season 4 ...". Anchored on a word boundary so a stray "s" inside a word
    // can't match; the LAST marker wins (titles put the season after the name).
    private val SEASON_IN_NAME = Regex("\\b(?:season\\s*|s)(\\d{1,2})\\b")

    // A pack entry: "Show S01-S05", "Show Season 1-5", "Show S1 to S4". It holds SEVERAL seasons, so it is not a
    // split-season entry for any one of them.
    private val SEASON_RANGE_IN_NAME =
        Regex("\\b(?:seasons?\\s*|s)\\d{1,2}\\s*(?:-|\u2013|\u2014|to)\\s*(?:seasons?\\s*|s)?\\d{1,2}\\b")

    /**
     * The season a catalog ENTRY stands for, read from its name — `5` for "Breaking Bad S5 En",
     * `null` for a whole-series entry like "Breaking Bad" (no season marker).
     */
    fun seasonInName(name: String): Int? {
        val lower = name.lowercase()
        // A range names no single season. Reading its LAST marker ("S01-S05" -> 5) hid the pack from seasons 1-4 and, for
        // season 5, matched episodes by number alone against its real-numbered list. As a whole-series entry it matches
        // on its own internal season numbers, which is what such a pack carries.
        if (SEASON_RANGE_IN_NAME.containsMatchIn(lower)) return null
        var found: Int? = null
        for (m in SEASON_IN_NAME.findAll(lower)) found = m.groupValues[1].toIntOrNull()
        return found
    }

    /**
     * Which entries are worth fetching get_series_info for, given the requested [season]: entries
     * that name exactly this season (split-season panels) plus whole-series entries with no season
     * marker (which may hold this season internally). Split entries for OTHER seasons are dropped so
     * we don't spend a network call per season of the show. De-duplicated by sid.
     */
    fun editionsForSeason(entries: List<IndexedItem>, season: Int): List<IndexedItem> =
        entries.distinctBy { it.sid }.filter {
            val named = seasonInName(it.name)
            named == null || named == season
        }

    /**
     * The episodes within one edition that satisfy the requested (season, episode). A split-season
     * entry IS its named season, so it only contributes when that season is the one asked for, and
     * then matches by episode number (its internal season is a flattened 1). A whole-series entry
     * uses its real internal season numbering, exactly as before.
     */
    fun pickEpisodes(edition: IndexedItem, episodes: List<XtreamEpisode>, season: Int, episode: Int): List<XtreamEpisode> {
        val named = seasonInName(edition.name)
        return if (named != null) {
            if (named != season) emptyList()
            else episodes.filter { it.episodeNum == episode }
        } else {
            episodes.filter { it.season == season && it.episodeNum == episode }
        }
    }
}
