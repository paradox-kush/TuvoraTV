package com.nuvio.tv.core.iptv.stalker

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps browse traffic off a Stalker portal while a stream from it is playing.
 *
 * Stalker accounts are usually sold with a very small connection allowance — often one. The portal
 * counts an in-flight `create_link` stream against it, so a guide that keeps pulling categories and
 * now/next while a channel plays can cost the viewer the picture they are watching. Our request
 * semaphore caps how MANY calls run at once; it has no idea that one of them is a live stream.
 *
 * Deliberately global rather than per-account: this app plays one thing at a time, so "something is
 * playing" and "something from this provider is playing" are the same statement, and a global count
 * needs no account plumbing through the player.
 *
 * This only ever DELAYS browse work, and only by [DEFER_SLICE_MS] at a time up to [MAX_DEFER_MS];
 * it must never fail a request, because a stuck counter would otherwise brick browsing.
 */
internal object StalkerPlaybackTraffic {

    /** How long a browse call waits before re-checking whether playback is still up. */
    const val DEFER_SLICE_MS = 300L

    /** Total time a browse call may be held back before it goes anyway. */
    const val MAX_DEFER_MS = 3_000L

    // A flag rather than a count, deliberately. Channel zapping re-reports "started" without an
    // intervening "stopped", so a counter would drift upward and never come back to zero; this app
    // only ever has one player, so "is something playing" is the whole question.
    private val playing = AtomicBoolean(false)

    val isPlaybackActive: Boolean get() = playing.get()

    fun onPlaybackStarted() {
        playing.set(true)
    }

    fun onPlaybackStopped() {
        playing.set(false)
    }

    /** Test seam — the flag is process-global. */
    internal fun resetForTests() {
        playing.set(false)
    }

    /**
     * Whether a browse call should wait, given what is playing and how long it has already waited.
     *
     * Pure so the ladder of "wait, re-check, eventually give up" is testable without a portal.
     * [isBootstrap] calls (handshake / get_profile) are never deferred: playback itself needs an
     * authenticated session, so holding those back would deadlock the thing we are protecting.
     */
    fun shouldDefer(playbackActive: Boolean, waitedMs: Long, isBootstrap: Boolean): Boolean =
        !isBootstrap && playbackActive && waitedMs < MAX_DEFER_MS
}
