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
     * How many *consecutive* failed re-tunes a channel gets before we stop and surface an error.
     *
     * Consecutive, not lifetime: a flaky feed that dies, recovers, plays for minutes and then dies
     * again has not exhausted anything — it has been working. Counting lifetime attempts left the
     * second death unrecoverable on an Onn 4K, 2026-08-18 (recovered at 14:05:03, played happily
     * to 14:08:42, then died with no attempts left and froze). [attemptsAfterSuccess] resets it.
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
    /**
     * How long a tune must actually play before we call the recovery a success.
     *
     * A single rendered frame is NOT proof: a channel that renders one frame and dies would reset
     * its budget on every attempt and reconnect for ever, which is the portal-hammering loop
     * [MAX_RECOVERY_ATTEMPTS] exists to prevent. Thirty seconds is comfortably longer than the
     * render-then-die pattern and far shorter than the multi-minute stretches a genuinely working
     * channel manages (the Onn feed played 3m 20s between deaths).
     */
    const val MIN_STABLE_PLAYBACK_MS = 30_000L

    /**
     * How long after we start a re-tune the player's own transitions are ignored.
     *
     * Re-preparing goes IDLE -> BUFFERING -> READY, and that IDLE lands straight back in the stall
     * handler. A boolean "in flight" flag does not cover it: the flag clears when the coroutine
     * that started the re-tune finishes, while the IDLE arrives asynchronously afterwards.
     * Measured on an Onn 4K, 2026-08-18 — re-tune at 14:24:58.819, self-inflicted IDLE at
     * 14:25:00.357, 1.5s later, which spent the second attempt on nothing.
     *
     * Five seconds comfortably covers a re-prepare while staying far below the interval between
     * genuine provider drops (minutes).
     */
    const val RETUNE_SETTLE_MS = 5_000L

    /**
     * Whether this stall is just our own re-tune settling, and must not count against the budget.
     *
     * @param msSinceRetuneStarted time since we asked for a re-tune, or null if none is pending.
     */
    fun isSelfInflictedTransition(msSinceRetuneStarted: Long?): Boolean =
        msSinceRetuneStarted != null && msSinceRetuneStarted < RETUNE_SETTLE_MS

    /**
     * Whether a stall begins a NEW incident rather than continuing the last one.
     *
     * True when the previous tune played long enough to count as working — that channel earned its
     * budget back. False for a feed that never really started, so its attempts keep counting down
     * and recovery eventually gives up instead of looping.
     */
    fun isNewIncident(playedMs: Long): Boolean = playedMs >= MIN_STABLE_PLAYBACK_MS

    /** The budget a new incident starts with. */
    fun attemptsAfterSuccess(): Int = 0

    /**
     * The rolling window, and how many re-tunes may happen inside it before we stop.
     *
     * The consecutive-failure budget alone is not enough. A feed that plays 30-60s and dies, over
     * and over, resets that budget every time and reconnects for ever — observed on an Onn 4K,
     * 2026-08-18: re-tunes at 15:05:38, 15:06:37 and 15:07:04, every one reported as attempt 1 of
     * 2, with no end in sight. Each re-tune is a fresh provider handshake, so an endless loop is
     * both useless to the viewer and abusive to the panel.
     *
     * Four re-tunes in five minutes tolerates a briefly flaky feed while catching one that is
     * simply broken.
     */
    const val RETUNE_WINDOW_MS = 5 * 60_000L
    const val MAX_RETUNES_PER_WINDOW = 4

    /**
     * Whether the recent re-tune history says this channel is looping rather than recovering.
     *
     * @param retuneTimestampsMs when recent re-tunes happened.
     * @param nowMs current time.
     */
    fun isRetuneLooping(retuneTimestampsMs: List<Long>, nowMs: Long): Boolean =
        retuneTimestampsMs.count { nowMs - it <= RETUNE_WINDOW_MS } >= MAX_RETUNES_PER_WINDOW

    /**
     * A one-line, reportable account of why the picture stopped.
     *
     * A frozen frame with no explanation is the worst possible bug report — "it froze" tells us
     * nothing about whether the provider ended the feed, the link expired, or the box lost the
     * network. This is what the viewer sees and what they paste into a report, so it names the
     * signal, how long the channel had been playing, and what recovery was attempted.
     *
     * @param playbackState the state that ended playback.
     * @param playedMs how long this tune had been playing before it stopped.
     * @param attemptsUsed re-tunes spent on this incident.
     * @param resolveError what the provider said when we asked for a fresh link, if it failed.
     */
    fun freezeReason(
        playbackState: Int,
        playedMs: Long,
        attemptsUsed: Int,
        resolveError: String? = null,
    ): String {
        val signal = when (playbackState) {
            STATE_ENDED -> "the provider ended the stream"
            STATE_IDLE -> "the connection dropped"
            else -> "playback stopped unexpectedly"
        }
        val lasted = formatDuration(playedMs)
        val recovery = when {
            resolveError != null -> "couldn't get a new link ($resolveError)"
            attemptsUsed <= 0 -> "no reconnect attempted"
            attemptsUsed == 1 -> "reconnected once, it stopped again"
            else -> "reconnected $attemptsUsed times, it stopped again"
        }
        return "$signal after $lasted — $recovery"
    }

    /**
     * The one-line technical footprint of a freeze, for the viewer to quote in a report.
     *
     * Everything here is chosen because it changes what we would go and look at:
     * the container tells us which lane the stream took (a `.ts` answered as HLS is a known
     * failure), the host tells us which panel, the state code distinguishes an orderly end from a
     * dropped socket, and the attempt count says whether recovery even ran.
     *
     * **Never include the stream URL.** Xtream paths carry the account's username and password in
     * plain text (`/live/<user>/<pass>/<id>.ts`), so this deliberately takes only the host and a
     * container that was derived from the path — pinned by a test that feeds it a credentialed URL.
     */
    fun technicalDetail(
        container: String,
        host: String?,
        playbackState: Int,
        attemptsUsed: Int,
        engine: String = "ExoPlayer",
        appVersion: String? = null,
    ): String {
        val stateName = when (playbackState) {
            STATE_IDLE -> "IDLE"
            STATE_BUFFERING -> "BUFFERING"
            STATE_READY -> "READY"
            STATE_ENDED -> "ENDED"
            else -> "STATE_$playbackState"
        }
        return buildList {
            add(container)
            host?.takeIf { it.isNotBlank() }?.let { add(it) }
            add("$stateName($playbackState)")
            add("retry $attemptsUsed/$MAX_RECOVERY_ATTEMPTS")
            add(engine)
            appVersion?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString(" · ")
    }

    /**
     * Host of a stream URL, with any embedded credentials dropped.
     *
     * Returns null rather than guessing when the URL is unparseable — an absent host is better in
     * a bug report than a wrong one.
     */
    fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val afterScheme = url.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        val authority = afterScheme.substringBefore('/').substringBefore('?')
        // user:pass@host — keep only what follows the last '@'
        return authority.substringAfterLast('@').takeIf { it.isNotBlank() }
    }

    /** Short human duration: "45s", "3m 20s". Kept here so the reason string is testable. */
    internal fun formatDuration(ms: Long): String {
        if (ms <= 0) return "0s"
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
    }

    fun shouldSurfaceError(playbackState: Int, isLiveFeed: Boolean, attemptsUsed: Int): Boolean =
        isLiveFeed &&
            attemptsUsed >= MAX_RECOVERY_ATTEMPTS &&
            (playbackState == STATE_ENDED || playbackState == STATE_IDLE)
}
