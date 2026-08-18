package com.nuvio.tv.ui.screens.iptv

/**
 * Where UP/DOWN lands while the guide's preview is fullscreen, and how long the guide waits before
 * it acts on that.
 *
 * Pulled out of the composable so the two decisions that actually go wrong — wrapping at the ends
 * and what an unknown starting channel means — are testable without a player, a surface or a
 * provider connection.
 *
 * The list is a ring: stopping dead on the last channel reads as a broken remote, and it is the
 * same rule [com.nuvio.tv.core.iptv.XtreamLivePlaylist] applies in the full player, so one D-pad
 * press means the same thing however the viewer arrived.
 */
object LiveChannelZapPolicy {

    /**
     * How long a pending zap sits before it is tuned.
     *
     * Every tune is a fresh provider handshake and panels commonly cap concurrent connections at
     * 1, so walking ten channels must cost one connection, not ten. Long enough to swallow a held
     * key, short enough that a single deliberate press still feels immediate.
     */
    const val COMMIT_DELAY_MS = 450L

    /**
     * The index [delta] steps from [currentIndex] in a list of [size] channels, wrapping at both
     * ends, or null when there is nothing to move to.
     *
     * A [currentIndex] outside the list (nothing tuned yet, or the tuned channel belongs to a
     * category that is no longer on screen) is not an error: the viewer pressed a channel key, so
     * answer with the end of the list they were heading for.
     */
    fun targetIndex(currentIndex: Int, delta: Int, size: Int): Int? {
        if (size <= 0 || delta == 0) return null
        if (currentIndex < 0 || currentIndex >= size) return if (delta > 0) 0 else size - 1
        return ((currentIndex + delta) % size + size) % size
    }
}
