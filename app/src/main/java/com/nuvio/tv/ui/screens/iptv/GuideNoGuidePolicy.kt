package com.nuvio.tv.ui.screens.iptv

/**
 * UX331 / UX337 / UX359 / UX47 / UX64: a playlist with no EPG repeated "No guide data" on every row and offered
 * no way to the settings. Decide once per group, show the channel name dim across each strip, and give a channel
 * with no logo a monogram tile instead of an empty square.
 */
internal object GuideNoGuidePolicy {
    /** Rows that must have answered before "this playlist sent no guide" is a verdict, not a guess. */
    const val MIN_SAMPLE = 5

    /**
     * [loadedHasProgrammes]: for every row whose guide has been asked (stream id -> it holds any programme).
     * No guide only when enough rows answered and none of them holds a programme: one programme anywhere means
     * the playlist has a guide and the empty rows are simply gaps.
     */
    fun groupHasNoGuide(channelCount: Int, loadedHasProgrammes: Map<Int, Boolean>): Boolean {
        if (channelCount <= 0) return false
        if (loadedHasProgrammes.values.any { it }) return false
        return loadedHasProgrammes.size >= minOf(channelCount, MIN_SAMPLE)
    }

    /** First letter or digit of the name, upper case; "#" when it has none. */
    fun monogram(name: String): String =
        name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"

    fun needsMonogram(logo: String?): Boolean = logo.isNullOrBlank()
}

/**
 * UX275: a past window whose catch-up history is not loaded, or failed, said "No guide data for this time", which
 * reads as "nothing exists". Loading and failure are their own states (bounded-load rule: it ends, a failure is
 * never "empty", and Failed is re-asked by the viewer, never by a loop).
 */
internal object GuideHistoryStatusPolicy {
    enum class View { NONE, LOADING, FAILED }

    /** [load] is the EFFECTIVE status (a Loading past its deadline already reads Failed). */
    fun view(awayFromNow: Boolean, hasProgrammesInWindow: Boolean, load: com.nuvio.tv.core.iptv.LoadStatus): View = when {
        !awayFromNow || hasProgrammesInWindow -> View.NONE
        load is com.nuvio.tv.core.iptv.LoadStatus.Loading -> View.LOADING
        load is com.nuvio.tv.core.iptv.LoadStatus.Failed -> View.FAILED
        else -> View.NONE
    }
}

/**
 * UX107: where the LEFT mini-guide (the channel list over fullscreen video) opens. The playing channel, else the
 * aimed one: a hidden playing channel is no longer a row, and the old fallback (row 1) lost the viewer's place.
 */
internal object MiniGuideStartPolicy {
    fun startIndex(channelIds: List<String>, playingId: String?, aimedId: String?): Int =
        playingId?.let(channelIds::indexOf)?.takeIf { it >= 0 }
            ?: aimedId?.let(channelIds::indexOf)?.takeIf { it >= 0 }
            ?: 0
}
