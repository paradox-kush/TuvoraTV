package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.contracts.CatchUpContinuationPolicy
import com.nuvio.tv.core.iptv.stalker.StalkerArchiveSource
import com.nuvio.tv.core.iptv.stalker.StalkerReplayOutcome
import com.nuvio.tv.core.lifecycle.Disposable

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
    /** Step 0.3: replays are built on the playlist's ACTIVE server and never fail over themselves. */
    private val serverFailover: PlaylistServerFailover = PlaylistServerFailover.detached(),
    /** B117: the panel's measured clock-pair offset — the replay `start` is panel-local time. */
    private val panelClock: PanelClockSource = PanelClockSource.NONE,
    /** Mints a Stalker archive replay's single-use link (the portal's `create_link`); nothing by default. */
    private val stalkerArchive: StalkerArchiveSource = StalkerArchiveSource.NONE,
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
        /**
         * End of the window the link asks for ([CatchUpContinuationPolicy.replayWindowEndMs] on M3U:
         * the show plus what follows, so the next shows play on). Equals the programme's end otherwise.
         */
        val windowEndMs: Long = programme.endMs,
        /**
         * The link is spent by playing it (a Stalker archive `create_link`): the engine must not
         * re-open it, nothing may probe it, and only a fresh mint ([reopenStalker]) recovers it.
         */
        val singleUse: Boolean = false,
    )

    /** How a bounded [open] ended. A failure is never "unavailable"; the viewer gets to retry it. */
    sealed interface OpenOutcome {
        data class Opened(val session: Session) : OpenOutcome

        /** The provider has no link for this programme (nothing to build, or every shape exhausted). */
        data object Unavailable : OpenOutcome

        /** The open itself failed or hit its deadline ([timedOut]). */
        data class Failed(val timedOut: Boolean) : OpenOutcome

        /** The Stalker portal answered with something specific; [failure] has the words and carries Retry. */
        data class Refused(val failure: StalkerReplayOutcome.Failure) : OpenOutcome
    }

    /** How a fresh mint for a single-use replay ended. */
    sealed interface Reopened {
        data class Link(val url: String) : Reopened {
            override fun toString(): String = "Reopened.Link(hasUrl=true)"
        }

        data class Refused(val failure: StalkerReplayOutcome.Failure) : Reopened
    }

    /** What a Stalker archive replay needs to be minted again: the portal account and the programme's own id. */
    private class StalkerReplay(val account: XtreamAccount, val programmeId: String, val session: Session)

    /** One M3U replay's candidate links, best first, and where the walk is. */
    private class M3uLadder(val primary: String, val urls: List<String>, var index: Int = 0)

    /**
     * ONE lock owns every map below and the token counters. `begin` runs detached under [open]'s
     * [BoundedLoad] (the caller's dispatcher, but not its child) while the player reads and advances
     * the same replay from `Player.Listener`, so "main-thread only" no longer holds. Held only for map
     * operations, never across a suspend or a URL build.
     */
    private val lock = Any()

    private val m3uLadders = HashMap<String, M3uLadder>()
    private var m3uToken = 0L

    /** [open] calls that are still waiting; a result for any other ticket is stale and must not be published. */
    private val liveTickets = HashSet<Long>()
    private var ticketSeq = 0L

    /**
     * What the player needs after the first frame, when [sessions] has already let go of the replay:
     * the account/channel to look the next programme up for, and the live URL to rejoin. Bounded to
     * the last few replays; nothing in the player's lifecycle calls back to drop it.
     *
     * One shape for both providers: an M3U replay carries its playlist's [info], an Xtream replay the
     * [request] it asked the panel with (a restart re-asks it from the playhead).
     */
    class ReplayContext(
        val account: XtreamAccount,
        val streamId: Int,
        val channelName: String,
        /** The channel's live URL, to rejoin live at the live edge; null when the caller had none. */
        val liveUrl: String?,
        val info: M3uCatchUpInfo?,
        val request: CatchUpDialectWalk.Request?,
        @Volatile var startMs: Long,
        @Volatile var windowEndMs: Long,
        val programmeEndMs: Long,
        val programmeTitle: String,
    )

    private val contexts = object : LinkedHashMap<String, ReplayContext>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ReplayContext>?): Boolean = size > 8
    }

    private val stalkerReplays = object : LinkedHashMap<String, StalkerReplay>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StalkerReplay>?): Boolean = size > 8
    }

    private val sessions = HashMap<String, Session>()

    /** Publishes a built replay unless its [ticket] was abandoned (the open timed out); true when it did. */
    private fun publish(ticket: Long?, session: Session, ladder: M3uLadder? = null, context: ReplayContext? = null): Boolean =
        synchronized(lock) {
            if (ticket != null && ticket !in liveTickets) return@synchronized false
            sessions[session.contentId] = session
            if (ladder != null) m3uLadders[session.contentId] = ladder
            if (context != null) contexts[session.contentId] = context
            true
        }

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
    suspend fun begin(
        account: XtreamAccount,
        channelContentId: String,
        channelName: String,
        streamId: Int,
        programme: Programme,
        nowMs: Long,
        allowedOutputFormats: List<String>? = null,
        /** An M3U channel's own live URL and catch-up settings — selects the M3U path over the Xtream dialects. */
        liveUrl: String? = null,
        m3uCatchUp: M3uCatchUpInfo? = null,
        ticket: Long? = null,
    ): Session? {
        if (m3uCatchUp != null) {
            return beginM3u(account, channelContentId, channelName, streamId, liveUrl, m3uCatchUp, programme, nowMs, ticket)
        }
        if (!supports(account)) return null
        if (streamId <= 0 || programme.startMs <= 0L || programme.endMs <= programme.startMs) return null

        // The preference has to be declared before the walk reads the winner memory, or a winner
        // proven under the OTHER container would lead the ladder and the toggle would do nothing.
        winners.useAccountPreference(account.id, account.preferM3u8CatchUp)

        // The show plus what follows, like an M3U replay, so playback runs on into the next programme.
        // An airing programme's end is in the future and is sent UNCLAMPED (panels serve what they
        // have, and clamping asks for a shorter recording than exists): the window is then its end.
        val windowEnd = CatchUpContinuationPolicy.replayWindowEndMs(
            CatchUpContinuationPolicy.Programme(programme.startMs, programme.endMs), nowMs,
        )
        val request = CatchUpDialectWalk.Request(
            accountId = account.id,
            baseUrl = serverFailover.activeAccount(account).baseUrl,
            username = account.username,
            password = account.password,
            streamId = streamId,
            startMs = programme.startMs,
            endMs = windowEnd,
            allowedOutputFormats = allowedOutputFormats,
            preferM3u8 = account.preferM3u8CatchUp,
            // B117: the panel's own wall clock (measured once per session) + the manual correction.
            serverOffsetMs = XtreamCatchUp.replayOffsetMs(panelClock.offsetMs(account), account.catchUpCorrectionMinutes),
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
            windowEndMs = windowEnd,
        )
        val context = ReplayContext(
            account, streamId, channelName, liveUrl?.takeIf { it.isNotBlank() }, null, request,
            programme.startMs, windowEnd, programme.endMs, programme.title,
        )
        return session.takeIf { publish(ticket, it, context = context) }
    }

    /**
     * [begin] under the live-resolve deadline (20 s, [BoundedLoad]). The guide used to wait on it with
     * no limit — a panel that never answers the clock-pair request held the replay press forever.
     * Ends as [OpenOutcome.Opened], [OpenOutcome.Unavailable] or [OpenOutcome.Failed]; the work runs
     * detached, so a call that ignores cancellation still cannot hold the screen.
     */
    suspend fun open(
        account: XtreamAccount,
        channelContentId: String,
        channelName: String,
        streamId: Int,
        programme: Programme,
        nowMs: Long,
        allowedOutputFormats: List<String>? = null,
        liveUrl: String? = null,
        m3uCatchUp: M3uCatchUpInfo? = null,
        /** A Stalker programme's own EPG id: the whole address of its recording (null = the portal named none). */
        stalkerProgrammeId: String? = null,
    ): OpenOutcome {
        if (account.sourceType == XtreamAccount.SOURCE_STALKER) {
            return openStalker(account, channelContentId, channelName, programme, nowMs, stalkerProgrammeId)
        }
        val ticket = synchronized(lock) { (++ticketSeq).also { liveTickets.add(it) } }
        val outcome = BoundedLoad.run<Session?>(
            surface = LoadSurface.LIVE_RESOLVE,
            isEmpty = { it == null },
            report = mapOf("source_type" to account.sourceType, "kind" to "catchup"),
        ) {
            begin(account, channelContentId, channelName, streamId, programme, nowMs, allowedOutputFormats, liveUrl, m3uCatchUp, ticket)
        }
        // Done or given up: either way a begin still running for this ticket must not publish any more.
        synchronized(lock) { liveTickets.remove(ticket) }
        return when (outcome) {
            is LoadOutcome.Loaded -> outcome.value?.let { OpenOutcome.Opened(it) } ?: OpenOutcome.Unavailable
            is LoadOutcome.Empty -> OpenOutcome.Unavailable
            is LoadOutcome.Failed -> OpenOutcome.Failed(outcome.timedOut)
        }
    }

    /**
     * M3U path: the playlist's own style builds the first link, [M3uCatchUp.alternatives] are the
     * fallbacks, and the form that played last time on this playlist leads. There is no panel to ask
     * for a clock, so the playlist's correction minutes are the whole offset.
     */
    private fun beginM3u(
        account: XtreamAccount,
        channelContentId: String,
        channelName: String,
        streamId: Int,
        liveUrl: String?,
        info: M3uCatchUpInfo,
        programme: Programme,
        nowMs: Long,
        ticket: Long?,
    ): Session? {
        if (!supports(account, info)) return null
        val live = liveUrl?.takeIf { it.isNotBlank() } ?: return null
        if (programme.startMs <= 0L || programme.endMs <= programme.startMs) return null
        val windowEnd = CatchUpContinuationPolicy.replayWindowEndMs(
            CatchUpContinuationPolicy.Programme(programme.startMs, programme.endMs), nowMs,
        )
        val offsetMs = account.catchUpOffsetMs ?: 0L
        val primary = M3uCatchUp.build(info, live, programme.startMs, windowEnd, nowMs, offsetMs) ?: return null
        val airing = programme.endMs > nowMs
        val alternatives = M3uCatchUp.alternatives(
            url = primary,
            offsetMs = offsetMs,
            showMinutes = (programme.endMs - programme.startMs) / 60_000L,
            airedMinutes = if (airing) (nowMs - programme.startMs) / 60_000L else null,
        )
        val urls = M3uCatchUp.preferForm(primary, alternatives, winners.recallM3uForm(account.id))
        val token = synchronized(lock) { ++m3uToken }
        val session = Session(
            contentId = contentIdFor(channelContentId, programme.startMs),
            accountId = account.id,
            channelName = channelName,
            programme = programme,
            url = urls.first(),
            token = token,
            isStartOver = airing,
            windowEndMs = windowEnd,
        )
        val context = ReplayContext(
            account, streamId, channelName, live, info, null, programme.startMs, windowEnd, programme.endMs, programme.title,
        )
        return session.takeIf { publish(ticket, it, M3uLadder(primary, urls), context) }
    }

    /** The context of a replay that asked for a continuation window (M3U or Xtream); null for any other. */
    fun replayContextFor(contentId: String?): ReplayContext? =
        contentId?.let { synchronized(lock) { contexts[it] } }

    /**
     * Ties the replay's context to its owner: the player adopts the handle into its scope and the
     * context goes with it. The bounded table above stays only as a backstop for a player that never disposes.
     */
    fun hold(contentId: String?): Disposable {
        var done = false
        return Disposable {
            val first = synchronized(lock) { !done.also { done = true } }
            if (first) release(contentId)
        }
    }

    /**
     * The replay is past the end of its window: ask for a new one starting at [atMs] (the playhead).
     * The window is [atMs, atMs + [CatchUpContinuationPolicy.CONTINUE_MS]] capped at what has aired.
     * Answers the first link, or null when none can be built (or the replay is gone).
     */
    fun restartReplay(contentId: String?, atMs: Long, nowMs: Long): Restart? {
        val ctx = replayContextFor(contentId) ?: return null
        return if (ctx.request != null) restartXtream(ctx, atMs, nowMs) else restartM3u(contentId!!, ctx, atMs, nowMs)
    }

    private fun restartM3u(contentId: String, ctx: ReplayContext, atMs: Long, nowMs: Long): Restart? {
        val info = ctx.info ?: return null
        val live = ctx.liveUrl ?: return null
        val windowEnd = CatchUpContinuationPolicy.replayWindowEndMs(
            CatchUpContinuationPolicy.Programme(atMs, atMs + 1L), nowMs,
        )
        val offsetMs = ctx.account.catchUpOffsetMs ?: 0L
        val primary = M3uCatchUp.build(info, live, atMs, windowEnd, nowMs, offsetMs) ?: return null
        val urls = M3uCatchUp.preferForm(
            primary,
            M3uCatchUp.alternatives(url = primary, offsetMs = offsetMs),
            winners.recallM3uForm(ctx.account.id),
        )
        synchronized(lock) {
            ctx.startMs = atMs
            ctx.windowEndMs = windowEnd
            m3uLadders[contentId] = M3uLadder(primary, urls)
        }
        return Restart(url = urls.first(), startMs = atMs, windowEndMs = windowEnd)
    }

    /**
     * Xtream: the same ask from the playhead. The winner dialect the first window proved leads the walk
     * by itself. The player reports nothing back about a restarted window (the replay already played),
     * so the walk is let go as soon as its first link is taken, instead of waiting on a result that
     * never comes and sitting in the way of the account's next replay.
     */
    private fun restartXtream(ctx: ReplayContext, atMs: Long, nowMs: Long): Restart? {
        val base = ctx.request ?: return null
        winners.useAccountPreference(ctx.account.id, ctx.account.preferM3u8CatchUp)
        val request = CatchUpDialectWalk.continuationRequest(base, atMs, nowMs)
        val first = walk.begin(request) as? CatchUpDialectWalk.Step.Next ?: return null
        walk.abandon(first.attempt.token)
        synchronized(lock) {
            ctx.startMs = atMs
            ctx.windowEndMs = request.endMs
        }
        return Restart(url = first.attempt.url, startMs = atMs, windowEndMs = request.endMs)
    }

    data class Restart(val url: String, val startMs: Long, val windowEndMs: Long)

    fun sessionFor(contentId: String?): Session? = contentId?.let { synchronized(lock) { sessions[it] } }

    /** A frame reached the screen: the shape is proven and remembered for this account. */
    fun onPlayed(contentId: String?) {
        val session = sessionFor(contentId) ?: return
        if (session.singleUse) {
            // Nothing to remember: a Stalker archive has no URL shapes to learn. Its mint stays held for Retry.
            synchronized(lock) { sessions.remove(session.contentId) }
            return
        }
        val ladder = synchronized(lock) { m3uLadders.remove(session.contentId) }
        if (ladder != null) {
            winners.rememberM3uForm(session.accountId, M3uCatchUp.linkForm(session.url, ladder.primary))
        } else {
            walk.onSuccess(session.token)
        }
        synchronized(lock) { sessions.remove(session.contentId) }
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
        // A spent single-use link has no shape to walk to: only a fresh mint (reopenStalker) recovers it.
        if (session.singleUse) return null
        synchronized(lock) { m3uLadders[session.contentId] }?.let { return onM3uFailed(session, it, errorCode) }
        val step = walk.onFailure(session.token, CatchUpFailure.kindFor(errorCode))
        if (step !is CatchUpDialectWalk.Step.Next) {
            synchronized(lock) { sessions.remove(session.contentId) }
            return null
        }
        val next = session.copy(url = step.attempt.url, token = step.attempt.token)
        synchronized(lock) { sessions[next.contentId] = next }
        return next
    }

    private fun onM3uFailed(session: Session, ladder: M3uLadder, errorCode: Int): Session? = synchronized(lock) {
        // Same rule as the Xtream walk: only a transport-shaped failure could be a wrong link shape.
        val next = ladder.index + 1
        if (CatchUpFailure.kindFor(errorCode) != CatchUpDialectWalk.FailureKind.TRANSPORT || next >= ladder.urls.size) {
            sessions.remove(session.contentId)
            m3uLadders.remove(session.contentId)
            return@synchronized null
        }
        ladder.index = next
        val moved = session.copy(url = ladder.urls[next], token = ++m3uToken)
        sessions[moved.contentId] = moved
        moved
    }

    /** Drops a finished replay's bookkeeping when the player leaves without succeeding or failing. */
    fun release(contentId: String?) {
        contentId?.let {
            synchronized(lock) { sessions.remove(it); m3uLadders.remove(it); contexts.remove(it); stalkerReplays.remove(it) }
        }
    }

    /**
     * Opens a Stalker archive replay: ONE `create_link` (`type=tv_archive`) from the portal's own
     * programme id, under the live-resolve deadline. The link is single-use, so the session is marked
     * and the engine, the dialect walk and every probe leave it alone. No programme id is no recording.
     */
    private suspend fun openStalker(
        account: XtreamAccount,
        channelContentId: String,
        channelName: String,
        programme: Programme,
        nowMs: Long,
        programmeId: String?,
    ): OpenOutcome {
        if (!supportsStalkerArchive(account) || programmeId.isNullOrBlank()) return OpenOutcome.Unavailable
        if (programme.startMs <= 0L || programme.endMs <= programme.startMs) return OpenOutcome.Unavailable
        val outcome = BoundedLoad.run<StalkerArchiveSource.Minted>(
            surface = LoadSurface.LIVE_RESOLVE,
            report = mapOf("source_type" to account.sourceType, "kind" to "catchup"),
        ) { stalkerArchive.mint(account, programmeId) }
        return when (outcome) {
            is LoadOutcome.Loaded -> when (val minted = outcome.value) {
                is StalkerArchiveSource.Minted.Link -> {
                    val session = Session(
                        contentId = contentIdFor(channelContentId, programme.startMs),
                        accountId = account.id,
                        channelName = channelName,
                        programme = programme,
                        url = minted.url,
                        token = synchronized(lock) { ++m3uToken },
                        isStartOver = programme.endMs > nowMs,
                        singleUse = true,
                    )
                    synchronized(lock) {
                        sessions[session.contentId] = session
                        stalkerReplays[session.contentId] = StalkerReplay(account, programmeId, session)
                    }
                    OpenOutcome.Opened(session)
                }
                is StalkerArchiveSource.Minted.Refused -> OpenOutcome.Refused(StalkerReplayOutcome.forOpen(minted.error))
            }
            is LoadOutcome.Empty -> OpenOutcome.Unavailable
            is LoadOutcome.Failed ->
                OpenOutcome.Refused(StalkerReplayOutcome.forOpen(CatchUpFailure.StalkerLinkError.NONE, outcome.timedOut))
        }
    }

    /** True while [contentId] names a replay whose link is spent by playing (a Stalker archive). */
    fun isSingleUse(contentId: String?): Boolean =
        contentId != null && synchronized(lock) { contentId in stalkerReplays }

    /**
     * Retry of a single-use replay: a FRESH `create_link` for the same programme, never the spent URL.
     * Null when [contentId] is not a held Stalker replay (not one, or the player already let go of it).
     */
    suspend fun reopenStalker(contentId: String?): Reopened? {
        val held = contentId?.let { synchronized(lock) { stalkerReplays[it] } } ?: return null
        val outcome = BoundedLoad.run<StalkerArchiveSource.Minted>(
            surface = LoadSurface.LIVE_RESOLVE,
            report = mapOf("source_type" to held.account.sourceType, "kind" to "catchup"),
        ) { stalkerArchive.mint(held.account, held.programmeId) }
        return when (outcome) {
            is LoadOutcome.Loaded -> when (val minted = outcome.value) {
                is StalkerArchiveSource.Minted.Link -> synchronized(lock) {
                    // The player may have left while the portal answered: do not resurrect its replay.
                    if (contentId !in stalkerReplays) return@synchronized null
                    val next = held.session.copy(url = minted.url, token = ++m3uToken)
                    sessions[contentId] = next
                    stalkerReplays[contentId] = StalkerReplay(held.account, held.programmeId, next)
                    Reopened.Link(minted.url)
                }
                is StalkerArchiveSource.Minted.Refused -> Reopened.Refused(StalkerReplayOutcome.forOpen(minted.error))
            }
            is LoadOutcome.Empty -> null
            is LoadOutcome.Failed ->
                Reopened.Refused(StalkerReplayOutcome.forOpen(CatchUpFailure.StalkerLinkError.NONE, outcome.timedOut))
        }
    }

    /** Whether this playlist is a Stalker portal whose archive can be minted at all (no dialects apply). */
    fun supportsStalkerArchive(account: XtreamAccount): Boolean =
        account.sourceType == XtreamAccount.SOURCE_STALKER

    /**
     * Whether an M3U playlist can replay a channel: only one that carries catch-up settings, because
     * the playlist itself says how to build the link (there is no panel to ask).
     */
    fun supports(account: XtreamAccount, m3uCatchUp: M3uCatchUpInfo?): Boolean =
        m3uCatchUp != null &&
            (account.sourceType == XtreamAccount.SOURCE_URL || account.sourceType == XtreamAccount.SOURCE_FILE)

    /**
     * Whether this playlist can build Xtream catch-up URLs at all (credentials present).
     *
     * A Stalker portal builds its own archive URL server-side from a `create_link` cmd, so none of
     * the dialects apply to it. Offering a replay button that could never work is exactly the false
     * promise the four-state design exists to avoid.
     */
    fun supports(account: XtreamAccount): Boolean =
        account.sourceType == XtreamAccount.SOURCE_XTREAM &&
            account.baseUrl.isNotBlank() &&
            account.username.isNotBlank() &&
            account.password.isNotBlank()
}
