package com.nuvio.tv.ui.screens.iptv

/**
 * Which channels the live guide asks for now/next data for.
 *
 * EPG is fetched per channel (`get_short_epg`, one call each, cached), so the guide cannot ask for
 * a whole category at once without hammering the panel. It instead prefetches a window around the
 * channel of interest, nearest first, so the row the viewer is looking at resolves soonest.
 *
 * Both entry points — a category's channels arriving, and focus moving — go through here, because
 * they had drifted apart: opening a category primed a window of exactly one.
 */
internal object GuideEpgPrefetchPolicy {

    /**
     * Channels either side of the centre to prefetch along with it. Timeline rows show cells, so
     * this covers a screenful rather than just the neighbours.
     */
    const val RADIUS = 8

    /**
     * Indexes to prefetch around [center], nearest first and clipped to a list of [size].
     * Empty when [center] is not a real position in the list.
     */
    fun indexesAround(center: Int, size: Int, radius: Int = RADIUS): List<Int> {
        if (size <= 0 || center !in 0 until size) return emptyList()
        val out = ArrayList<Int>(radius * 2 + 1)
        out.add(center)
        for (d in 1..radius) {
            (center - d).takeIf { it >= 0 }?.let(out::add)
            (center + d).takeIf { it < size }?.let(out::add)
        }
        return out
    }

    /**
     * A category's channels just arrived. The guide marks the first channel as focused as it does
     * so, which means the focus event for it reads as "no change" and never reaches
     * [onFocusChanged] — so arriving here has to prime the same window that landing on the first
     * channel would have.
     */
    fun onChannelsLoaded(size: Int): List<Int> = indexesAround(center = 0, size = size)

    /** Focus settled on [center]. */
    fun onFocusChanged(center: Int, size: Int): List<Int> = indexesAround(center, size)
}
