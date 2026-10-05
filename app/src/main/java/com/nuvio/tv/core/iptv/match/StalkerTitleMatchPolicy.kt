package com.nuvio.tv.core.iptv.match

/** STUB (red step): today's rule — plain normKey equality. */
object StalkerTitleMatchPolicy {
    fun wantKeys(titles: List<String?>): Set<String> =
        titles.filterNotNull().map { TitleNormalizer.normKey(it) }.filter { it.isNotEmpty() }.toSet()
    fun movieMatches(name: String, wantKeys: Set<String>, year: Int?): Boolean = TitleNormalizer.normKey(name) in wantKeys
    fun seriesMatches(name: String, wantKeys: Set<String>, season: Int): Boolean = TitleNormalizer.normKey(name) in wantKeys
}
