package com.nuvio.tv.core.iptv.stalker

import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.ProviderResolutionTrigger

/**
 * Which identity a Stalker stream presents to the portal's own host, learned per playlist.
 *
 * The evidence: a real provider (portal and stream on the same host) answers the create_link stream
 * with HTTP 401 at the first hop whenever the request carries the MAG set-top-box User-Agent, and 200
 * with video for any other UA (or none). Cookie, Bearer, Referer, Origin and X-User-Agent make no
 * difference. Other providers may need the MAG identity on the stream, so it stays the default:
 *
 * - [StreamIdentity.IDENTITY] — [StalkerStreamHeaderPolicy]'s portal profile (MAG UA, X-User-Agent,
 *   origin-bound Cookie/Bearer/Referer/Origin). Every playlist starts here.
 * - [StreamIdentity.NEUTRAL] — the credential-free player profile a foreign host already gets.
 *
 * A tune (an INITIAL resolution: tune, zap, the viewer's Retry) starts on the playlist's remembered
 * identity. When the stream on the portal host is refused with an auth rejection (401/403 →
 * [FailureCode.AUTHORIZATION_REJECTED]) before it ever showed media, the next re-mint of that tune
 * presents the other identity — each identity at most once per tune, so a provider that refuses both
 * falls through to the normal failure handling instead of flip-flopping. The identity that then
 * plays is remembered for the playlist (in memory, per process). A playlist User-Agent override is
 * the user's decision: it is used as set and never switched.
 *
 * Such a switch also means the previous attempt held no connection (refused at open), so the re-mint
 * need not wait out [com.nuvio.tv.playback.core.SingleUseRemintSpacingPolicy] — see [switchesOnRemint].
 */
class StalkerStreamIdentityPolicy {

    enum class StreamIdentity { IDENTITY, NEUTRAL }

    private class Tune(
        var current: StreamIdentity,
        val tried: MutableSet<StreamIdentity>,
        var onPortalHost: Boolean,
        var played: Boolean = false,
    )

    /** Playlist id → the identity proven to play, when it is not the default. */
    private val learned = HashMap<String, StreamIdentity>()

    /** (playlist id, content key) → the tune in progress; bounded, oldest dropped first. */
    private val tunes = object : LinkedHashMap<Pair<String, String>, Tune>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, Tune>?): Boolean =
            size > MAX_TUNES
    }

    /** The identity this playlist starts a tune with. */
    @Synchronized
    fun remembered(playlistId: String): StreamIdentity = learned[playlistId] ?: StreamIdentity.IDENTITY

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
    ): Boolean {
        if (trigger == ProviderResolutionTrigger.INITIAL || hasUserAgentOverride) return false
        if (previousFailure != FailureCode.AUTHORIZATION_REJECTED) return false
        val tune = tunes[playlistId to contentKey] ?: return false
        return tune.onPortalHost && !tune.played && other(tune.current) !in tune.tried
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
    ): StreamIdentity {
        val key = playlistId to contentKey
        if (hasUserAgentOverride) {
            tunes.remove(key)
            return StreamIdentity.IDENTITY
        }
        val existing = tunes[key]
        if (trigger == ProviderResolutionTrigger.INITIAL || existing == null) {
            val start = remembered(playlistId)
            tunes[key] = Tune(start, mutableSetOf(start), onPortalHost)
            return start
        }
        if (switchesOnRemint(playlistId, contentKey, trigger, previousFailure, hasUserAgentOverride = false)) {
            existing.current = other(existing.current)
            existing.tried += existing.current
        }
        existing.onPortalHost = onPortalHost
        return existing.current
    }

    /** The tune showed media: its current identity passed the provider's check. */
    @Synchronized
    fun onPlaybackStarted(playlistId: String, contentKey: String) {
        val tune = tunes[playlistId to contentKey] ?: return
        tune.played = true
        if (!tune.onPortalHost) return
        when (tune.current) {
            StreamIdentity.NEUTRAL -> learned[playlistId] = StreamIdentity.NEUTRAL
            StreamIdentity.IDENTITY -> learned.remove(playlistId)
        }
    }

    private fun other(identity: StreamIdentity): StreamIdentity = when (identity) {
        StreamIdentity.IDENTITY -> StreamIdentity.NEUTRAL
        StreamIdentity.NEUTRAL -> StreamIdentity.IDENTITY
    }

    companion object {
        private const val MAX_TUNES = 32

        /** One per process: what a playlist learned outlives the per-session resolver. */
        val processWide = StalkerStreamIdentityPolicy()
    }
}
