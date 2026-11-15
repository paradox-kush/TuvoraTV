package com.nuvio.tv.core.multiview

/** A programme as a tile needs it: a title and when it airs. Nothing else is read or kept. */
internal data class TileProgramme(val title: String, val startMs: Long, val endMs: Long)

/**
 * Line 2 of a tile's label: the programme airing now, read from the guide's stored programmes at draw time. It is
 * absent when the guide has nothing airing now for that channel, never an old title and never a snapshot.
 */
internal object TileNowPlayingPolicy {
    fun nowTitle(programmes: List<TileProgramme>, nowMs: Long): String? =
        programmes.firstOrNull { nowMs >= it.startMs && nowMs < it.endMs }?.title?.takeIf { it.isNotBlank() }

    /**
     * The next moment the answer of [nowTitle] changes: when the airing programme ends, or the next one starts when
     * nothing airs. Null when no programme is left, so nothing is scheduled.
     */
    fun nextChangeAtMs(programmes: List<TileProgramme>, nowMs: Long): Long? {
        val airing = programmes.firstOrNull { nowMs >= it.startMs && nowMs < it.endMs }
        if (airing != null) return airing.endMs
        return programmes.filter { it.startMs > nowMs }.minOfOrNull { it.startMs }
    }
}
