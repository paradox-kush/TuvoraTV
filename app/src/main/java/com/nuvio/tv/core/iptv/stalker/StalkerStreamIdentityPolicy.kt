package com.nuvio.tv.core.iptv.stalker

import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.ProviderResolutionTrigger

/**
 * Which identity a Stalker live stream presents to the portal's own host, learned per playlist.
 *
 * The evidence: until 1.21.3 (e4178acc7) the TV never sent the MAG identity to a Stalker stream — only
 * the playlist's User-Agent override, or none (the engine default) — and providers played. 1.21.3
 * started sending the MAG identity on the portal host, and a real provider answers that with HTTP 401
 * at the first hop (any other User-Agent plays). So:
 *
 * - [StreamIdentity.NEUTRAL] — the pre-1.21.3 request ([StalkerStreamHeaderPolicy]: the override or no
 *   UA, no other header, no credentials). Every playlist starts here; every non-portal host gets it.
 * - [StreamIdentity.IDENTITY] — the portal identity (MAG UA, X-User-Agent, origin-bound
 *   Cookie/Bearer/Referer/Origin), for a provider that refuses the plain request.
 *
 * A tune (an INITIAL resolution: tune, zap, the viewer's Retry, in one playback session) starts on the
 * playlist's remembered identity. When the stream on the portal host is refused with an auth rejection
 * (401/403 → [FailureCode.AUTHORIZATION_REJECTED]) before it ever showed media, the next re-mint of that
 * tune is a one-shot TRIAL of the other identity. If the trial shows media, it is remembered for the
 * playlist (in memory, per process). If it fails before media, the rest of the reconnect ladder goes
 * back to the tune's starting identity with the normal spacing, so a refusal for a passing reason (the
 * previous connection still held, an expired token) recovers as it always did. One trial per tune, so
 * nothing flip-flops. A playlist User-Agent override is the user's decision: it is sent as set, on the
 * plain request, and never switched. Live streams only — VOD keeps the pre-1.21.3 request.
 *
 * Such a switch also means the previous attempt held no connection (refused at open), so the re-mint
 * need not wait out [com.nuvio.tv.playback.core.SingleUseRemintSpacingPolicy] — see [switchesOnRemint].
 */
class StalkerStreamIdentityPolicy {

    enum class StreamIdentity { IDENTITY, NEUTRAL }

    private class Tune(
        /** What the tune started with; a failed trial returns here. */
        val start: StreamIdentity,
        var current: StreamIdentity,
        val tried: MutableSet<StreamIdentity>,
        var onPortalHost: Boolean,
        var played: Boolean = false,
    )

    /** Playlist id → the identity proven to play, when it is not the default ([DEFAULT]). */
    private val learned = HashMap<String, StreamIdentity>()

    private data class TuneKey(val playlistId: String, val contentKey: String, val sessionKey: Long)

    /** (playlist, channel, playback session) → the tune in progress; bounded, oldest dropped first. */
    private val tunes = object : LinkedHashMap<TuneKey, Tune>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TuneKey, Tune>?): Boolean =
            size > MAX_TUNES
    }

    /** The identity this playlist starts a tune with. */
    @Synchronized
    fun remembered(playlistId: String): StreamIdentity = learned[playlistId] ?: DEFAULT

    /**
     * True when a re-mint with [trigger] after [previousFailure] would switch the identity. Pure query:
     * it changes nothing, so the session can ask it before deciding whether to wait.
     */
    @Synchronized
    fun switchesOnRemint(
        playlistId: String,
        contentKey: String,
        trigger: ProviderResolutionTrigger,
        previousFailure: FailureCode?,
        hasUserAgentOverride: Boolean,
        sessionKey: Long = 0L,
    ): Boolean {
        if (trigger == ProviderResolutionTrigger.INITIAL || hasUserAgentOverride) return false
        if (previousFailure != FailureCode.AUTHORIZATION_REJECTED) return false
        val tune = tunes[TuneKey(playlistId, contentKey, sessionKey)] ?: return false
        return tune.onPortalHost && !tune.played && tune.current == tune.start && other(tune.current) !in tune.tried
    }

    /**
     * The identity for the link just minted, recording it as the tune's current attempt.
     * [onPortalHost]: the minted stream is on the portal's host (only there does the identity apply).
     */
    @Synchronized
    fun identityFor(
        playlistId: String,
        contentKey: String,
        trigger: ProviderResolutionTrigger,
        previousFailure: FailureCode?,
        hasUserAgentOverride: Boolean,
        onPortalHost: Boolean,
        sessionKey: Long = 0L,
    ): StreamIdentity {
        val key = TuneKey(playlistId, contentKey, sessionKey)
        if (hasUserAgentOverride) {
            tunes.remove(key)
            return StreamIdentity.NEUTRAL
        }
        val existing = tunes[key]
        if (trigger == ProviderResolutionTrigger.INITIAL || existing == null) {
            val start = remembered(playlistId)
            tunes[key] = Tune(start, start, mutableSetOf(start), onPortalHost)
            return start
        }
        if (switchesOnRemint(playlistId, contentKey, trigger, previousFailure, hasUserAgentOverride = false, sessionKey)) {
            existing.current = other(existing.current)
            existing.tried += existing.current
        } else if (existing.current != existing.start && !existing.played) {
            // The trial failed before any media: back to the starting identity, spaced as usual.
            existing.current = existing.start
        }
        existing.onPortalHost = onPortalHost
        return existing.current
    }

    /** The tune showed media: its current identity passed the provider's check. */
    @Synchronized
    fun onPlaybackStarted(playlistId: String, contentKey: String, sessionKey: Long = 0L) {
        val tune = tunes[TuneKey(playlistId, contentKey, sessionKey)] ?: return
        tune.played = true
        if (!tune.onPortalHost) return
        if (tune.current == DEFAULT) learned.remove(playlistId) else learned[playlistId] = tune.current
    }

    private fun other(identity: StreamIdentity): StreamIdentity = when (identity) {
        StreamIdentity.IDENTITY -> StreamIdentity.NEUTRAL
        StreamIdentity.NEUTRAL -> StreamIdentity.IDENTITY
    }

    companion object {
        private const val MAX_TUNES = 32

        /** The pre-1.21.3 request: what every playlist starts with. */
        val DEFAULT = StreamIdentity.NEUTRAL

        /** One per process: what a playlist learned outlives the per-session resolver. */
        val processWide = StalkerStreamIdentityPolicy()
    }
}
