package com.nuvio.tv.ui.screens.iptv

/**
 * Whether the guide's preview player has silently died and should be re-tuned.
 *
 * The preview runs ExoPlayer (mpv's `vo=gpu` starved the guide UI on a 2GB Onn), and its listener
 * only ever handled [androidx.media3.common.Player.Listener.onPlayerError]. A provider that closes
 * the socket mid-stream does **not** raise an error: ExoPlayer reports STATE_ENDED, AudioTrack
 * stops, and playback simply stops. No error, so nothing fired, so nothing recovered — the picture
 * froze and the app did not know.
 *
 * Observed on the user's Onn 4K, 2026-08-18: `AudioTrack: stop(702) called with 4038656 frames
 * delivered`, decode stopped one second later, and the remaining minutes of log contain no error,
 * no freeze report and no re-tune. The freeze ladder that would have caught it
 * ([com.nuvio.tv.ui.screens.player.PlayerLiveSamplingPolicy]) lives in PlayerScreen and has never
 * been wired to this surface — a gap that only mattered once the guide could be watched fullscreen
 * for long stretches.
 *
 * The rule is the same one the player already states: **a live channel has no end.** ENDED or IDLE
 * on a live feed is a dropped feed, never a completion.
 *
 * Pure so the decision is pinned by tests rather than only reproducible by waiting for a provider
 * to drop a socket.
 */
object GuidePreviewFreezePolicy {

    /** ExoPlayer states this policy reasons about, named so tests do not depend on media3. */
    const val STATE_IDLE = 1
    const val STATE_BUFFERING = 2
    const val STATE_READY = 3
    const val STATE_ENDED = 4

    /**
     * How many automatic re-tunes a single channel gets before we stop and surface an error.
     *
     * Deliberately small: every re-tune is a fresh provider handshake, panels commonly cap
     * concurrent connections at 1, and a genuinely dead channel must not turn into an infinite
     * reconnect loop hammering the portal.
     */
    const val MAX_RECOVERY_ATTEMPTS = 2

    /**
     * Whether [playbackState] on the preview surface means "this feed died, re-tune it".
     *
     * @param playbackState the ExoPlayer playback state just reported.
     * @param isLiveFeed a true live channel. Catch-up is a recording and really does end — it also
     *   never plays in this surface, but the flag keeps the rule honest if that ever changes.
     * @param attemptsUsed how many automatic recoveries this channel has already consumed.
     */
    fun shouldRetune(playbackState: Int, isLiveFeed: Boolean, attemptsUsed: Int): Boolean {
        if (!isLiveFeed) return false
        if (attemptsUsed >= MAX_RECOVERY_ATTEMPTS) return false
        return playbackState == STATE_ENDED || playbackState == STATE_IDLE
    }

    /**
     * Whether the viewer should be told the channel is gone, rather than silently sitting on a
     * frozen frame — true once the feed died and the automatic attempts are spent.
     */
    fun shouldSurfaceError(playbackState: Int, isLiveFeed: Boolean, attemptsUsed: Int): Boolean =
        isLiveFeed &&
            attemptsUsed >= MAX_RECOVERY_ATTEMPTS &&
            (playbackState == STATE_ENDED || playbackState == STATE_IDLE)
}
