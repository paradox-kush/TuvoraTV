package com.nuvio.tv.core.iptv

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds one catch-up replay across the guide → player boundary.
 *
 * The guide decides WHAT to replay and asks for the first URL; the player is the only thing that
 * can say whether it actually played. That is deliberate — the dialect walk must run on the REAL
 * playback attempt and never on an out-of-band probe, because on a `max_connections=1` account a
 * probe kicks the viewer's own live stream (the same reason the .ts→HLS fix refused to probe).
 * Since those two halves live on either side of a navigation, the walk has to sit here rather than
 * in either of them.
 *
 * Main-thread only, like [CatchUpDialectWalk] it wraps: the guide calls in from a ViewModel and the
 * player from `Player.Listener`, both of which are main-looper-bound.
 */
@Singleton
class CatchUpPlaybackCoordinator @Inject constructor(
    private val winners: CatchUpWinnerStore,
) {
    private val walk = CatchUpDialectWalk(winners)

    /** The programme being replayed, as the guide knows it. */
    data class Programme(
        val title: String,
        val startMs: Long,
        val endMs: Long,
    )

    /** One live replay attempt, addressable by the content id the player was launched with. */
    data class Session(
        val contentId: String,
        val accountId: String,
        val channelName: String,
        val programme: Programme,
        val url: String,
        val token: Long,
        /** True when the programme is still airing — the case whose seek ceiling is clamped. */
        val isStartOver: Boolean,
    )

    private val sessions = HashMap<String, Session>()

    /**
     * The replay's content id. Deliberately keeps the channel's `:live:` segment plus the start
     * minute, matching the shape the Sports replays already use — the id stays a live-channel id
     * and `isCatchUpPlayback` carries the difference, rather than a new id scheme that every
     * `:live:` check in the app would have to learn.
     */
    fun contentIdFor(channelContentId: String, programmeStartMs: Long): String =
        "${channelContentId}r${programmeStartMs / 60_000L}"

    /**
     * Starts a replay and answers the first URL to try, or null when nothing can be built (blank
     * credentials, a non-Xtream source, a degenerate programme).
     */
    fun begin(
        account: XtreamAccount,
        channelContentId: String,
        channelName: String,
        streamId: Int,
        programme: Programme,
        nowMs: Long,
        allowedOutputFormats: List<String>? = null,
    ): Session? {
        if (!supports(account)) return null
        if (streamId <= 0 || programme.startMs <= 0L || programme.endMs <= programme.startMs) return null

        // The preference has to be declared before the walk reads the winner memory, or a winner
        // proven under the OTHER container would lead the ladder and the toggle would do nothing.
        winners.useAccountPreference(account.id, account.preferM3u8CatchUp)

        val request = CatchUpDialectWalk.Request(
            accountId = account.id,
            baseUrl = account.baseUrl,
            username = account.username,
            password = account.password,
            streamId = streamId,
            // The airing programme's end is in the future and is sent UNCLAMPED: panels serve what
            // they have, and clamping asks for a shorter recording than exists.
            startMs = programme.startMs,
            endMs = programme.endMs,
            allowedOutputFormats = allowedOutputFormats,
            preferM3u8 = account.preferM3u8CatchUp,
            serverOffsetMs = account.catchUpOffsetMs,
        )
        val step = walk.begin(request) as? CatchUpDialectWalk.Step.Next ?: return null
        val session = Session(
            contentId = contentIdFor(channelContentId, programme.startMs),
            accountId = account.id,
            channelName = channelName,
            programme = programme,
            url = step.attempt.url,
            token = step.attempt.token,
            isStartOver = programme.endMs > nowMs,
        )
        sessions[session.contentId] = session
        return session
    }

    fun sessionFor(contentId: String?): Session? = contentId?.let { sessions[it] }

    /** A frame reached the screen: the shape is proven and remembered for this account. */
    fun onPlayed(contentId: String?) {
        val session = sessionFor(contentId) ?: return
        walk.onSuccess(session.token)
        sessions.remove(session.contentId)
    }

    /**
     * Playback failed. Answers the next URL to try, or null when the walk is over — at which point
     * the caller tells the viewer the programme is unavailable rather than retrying forever.
     *
     * Only transport-shaped failures advance ([CatchUpFailure.kindFor]); a decode failure means the
     * URL reached a stream, so another shape fetches the same broken recording.
     */
    fun onFailed(contentId: String?, errorCode: Int): Session? {
        val session = sessionFor(contentId) ?: return null
        val step = walk.onFailure(session.token, CatchUpFailure.kindFor(errorCode))
        if (step !is CatchUpDialectWalk.Step.Next) {
            sessions.remove(session.contentId)
            return null
        }
        val next = session.copy(url = step.attempt.url, token = step.attempt.token)
        sessions[next.contentId] = next
        return next
    }

    /** Drops a finished replay's bookkeeping when the player leaves without succeeding or failing. */
    fun release(contentId: String?) {
        contentId?.let { sessions.remove(it) }
    }

    /**
     * Whether this playlist can build catch-up URLs at all.
     *
     * Xtream only. A Stalker portal builds its own archive URL server-side from a `create_link`
     * cmd, so none of these dialects apply to it — and an M3U playlist has no panel to ask. Both
     * would otherwise offer a replay button that could never work, which is exactly the false
     * promise the four-state design exists to avoid.
     */
    fun supports(account: XtreamAccount): Boolean =
        account.sourceType == XtreamAccount.SOURCE_XTREAM &&
            account.baseUrl.isNotBlank() &&
            account.username.isNotBlank() &&
            account.password.isNotBlank()
}
