package com.nuvio.tv.ui.screens.player

/**
 * Whether the progress loop must keep running after the player stops reporting "playing".
 *
 * The live-freeze detector ([sampleLiveFreeze]) is sampled from inside the progress job, and
 * `onIsPlayingChanged(false)` used to cancel that job unconditionally whenever the state was
 * ENDED or IDLE. For VOD that is right — the episode finished, nothing left to sample.
 *
 * For a LIVE feed it was the whole bug: a provider closing the socket mid-stream surfaces as
 * ENDED, which is precisely the freeze [LivePlaybackFreezePolicy] exists to catch ("a live
 * channel has no end"), and cancelling the job there **killed the sampler at the exact moment
 * it was needed**. The reconnect ladder was fully built and wired, and could never run for the
 * ENDED case — the picture just stopped until the viewer restarted the channel by hand.
 * Observed on the user's Onn 4K box, 2026-08-17.
 *
 * Mobile never had this: its docked live screen drives the detector from a push `onSnapshot`
 * callback rather than a cancellable poll loop, which is why the same freeze self-heals there.
 */
internal object PlayerLiveSamplingPolicy {

    /**
     * @param isLiveFeed a true live channel (catch-up replay is a recording: it really does end)
     * @param isEndedOrIdle the player reported STATE_ENDED or STATE_IDLE
     */
    fun shouldKeepSamplingWhileNotPlaying(isLiveFeed: Boolean, isEndedOrIdle: Boolean): Boolean =
        isLiveFeed || !isEndedOrIdle

    /**
     * Whether an ENDED event may be treated as an episode finishing (marks watched, chains
     * auto-play next). A live channel reaching ENDED is a dropped feed, never a completion.
     */
    fun isNaturalCompletionCandidate(isLiveFeed: Boolean): Boolean = !isLiveFeed
}
