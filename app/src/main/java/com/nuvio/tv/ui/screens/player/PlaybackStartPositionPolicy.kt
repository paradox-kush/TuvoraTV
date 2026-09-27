package com.nuvio.tv.ui.screens.player

/**
 * Where a source swap (IPTV link refresh, auto-retry) should reopen the file.
 *
 * The resume target belongs to the playback request, not to whichever load happened to carry it:
 * until a frame has rendered the player reports 0 (or nothing), and the first load has already
 * consumed the pending resume, so reading the player position reopened a resume at 0:00 (B59).
 * Before the first frame the requested start wins; after it, wherever the viewer actually is.
 * Live always rejoins at the edge.
 */
internal object PlaybackStartPositionPolicy {
    fun targetAfterSourceSwap(
        isLive: Boolean,
        firstFrameShown: Boolean,
        currentPositionMs: Long?,
        requestedStartMs: Long,
    ): Long {
        if (isLive) return 0L
        val current = currentPositionMs?.coerceAtLeast(0L) ?: 0L
        return if (firstFrameShown) current else maxOf(current, requestedStartMs.coerceAtLeast(0L))
    }
}
