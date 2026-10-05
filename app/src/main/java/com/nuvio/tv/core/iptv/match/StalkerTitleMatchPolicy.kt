package com.nuvio.tv.core.iptv.match

/**
 * Whether a Stalker portal's search hit is the TMDB title an add-on catalog asked for (B122).
 *
 * Stalker content never enters the TMDB match index (a 14-rows-a-page portal can't be mirrored), so
 * the source lane asks the portal's own search and has to decide here. It used to require the hit's
 * plain [TitleNormalizer.normKey] to EQUAL the title's — so the everyday portal naming
 * ("EN - The Matrix (1999)", "|EN| The Matrix", "The Matrix 4K") never matched, and Stalker playlists
 * were never offered as a source while Xtream ones were. Now a hit matches under the SAME keys the
 * Xtream index files every catalog name under ([TitleNormalizer.keysOf]: provider tags, years,
 * quality and language suffixes stripped), probed with the title's exact-tier keys, exactly as the
 * index is. Movies keep the ±1 year guard; a split-season series entry ("Breaking Bad S5") is only
 * offered for its own season, because the episode link is minted later, unverified, at pick time.
 */
object StalkerTitleMatchPolicy {

    /** The exact-tier probe keys for a TMDB title bundle (primary, original). */
    fun wantKeys(titles: List<String?>): Set<String> =
        titles.filterNotNull().map { TitleNormalizer.normKey(it) }.filter { it.isNotEmpty() }.toSet()

    fun movieMatches(name: String, wantKeys: Set<String>, year: Int?): Boolean =
        keyMatch(name, wantKeys) && yearCompatible(TitleNormalizer.yearOf(name), year)

    fun seriesMatches(name: String, wantKeys: Set<String>, season: Int): Boolean {
        if (!keyMatch(name, wantKeys)) return false
        val marked = seasonMarkerOf(name)
        return marked == null || marked == season
    }

    private fun keyMatch(name: String, wantKeys: Set<String>): Boolean {
        if (wantKeys.isEmpty() || name.isBlank()) return false
        // "|EN| Title": keysOf's leading-tag strip needs the tag to START the name.
        val forms = linkedSetOf(name, name.trimStart('|', ' '))
        return forms.any { f -> TitleNormalizer.keysOf(f).any { it in wantKeys } }
    }

    /** "Breaking Bad S5" / "… Season 3" -> 5 / 3; null when the name carries no season marker. */
    internal fun seasonMarkerOf(name: String): Int? =
        SEASON_MARKER.find(TitleNormalizer.fold(name))?.groupValues?.get(1)?.toIntOrNull()

    private fun yearCompatible(a: Int?, b: Int?): Boolean =
        a == null || b == null || (if (a > b) a - b else b - a) <= 1

    private val SEASON_MARKER = Regex("""\b(?:s|season)\s*(\d{1,2})\b""")
}
