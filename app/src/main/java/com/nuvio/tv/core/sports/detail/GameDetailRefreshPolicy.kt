package com.nuvio.tv.core.sports.detail

/**
 * What to fetch now, and when to look again, for an open game screen. Pure: state + clock in,
 * parts out, so it tests without the network (house pattern: RadarLiveRefreshPolicy).
 *
 * Recurring-network rule: delta not dump (live ticks carry only the scoreboard; the summary rides a
 * slower clock), lifecycle-bound (a hidden screen requests nothing), and idle = zero requests (a
 * finished or far-off game fetches once, then [Decision.nextDelayMs] is null and nothing wakes).
 */
object GameDetailRefreshPolicy {
    data class Config(
        val liveMs: Long = 15_000,
        val summaryMs: Long = 60_000,
        /** Scoreboard cadence for an upcoming game inside its kick-off window, to catch the flip to live. */
        val kickoffPollMs: Long = 60_000,
        /** How long before the scheduled start an upcoming game starts being polled. */
        val kickoffLeadMs: Long = 120_000,
    ) {
        companion object {
            /** Remote cadences are clamped to the mapping's own floor so a bad push can't hammer the provider. */
            fun from(r: RefreshConfig) = Config(
                liveMs = maxOf(r.liveMs, r.minMs),
                summaryMs = maxOf(r.summaryMs, r.minMs),
                kickoffPollMs = maxOf(60_000, r.minMs),
            )
        }
    }

    data class Decision(val fetch: Set<RefreshPart>, val nextDelayMs: Long?)

    private val none = Decision(emptySet(), null)

    fun decide(
        state: GameState,
        visible: Boolean,
        nowMs: Long,
        lastScoreboardMs: Long?,
        lastSummaryMs: Long?,
        startsAtMs: Long,
        config: Config = Config(),
    ): Decision {
        if (!visible) return none
        return when (state) {
            GameState.LIVE -> cadence(nowMs, lastScoreboardMs, config.liveMs, lastSummaryMs, config.summaryMs)
            GameState.UPCOMING ->
                if (nowMs >= startsAtMs - config.kickoffLeadMs) {
                    cadence(nowMs, lastScoreboardMs, config.kickoffPollMs, lastSummaryMs, null)
                } else once(lastScoreboardMs, lastSummaryMs)
            GameState.FINAL, GameState.POSTPONED -> once(lastScoreboardMs, lastSummaryMs)
        }
    }

    private fun once(board: Long?, summary: Long?): Decision {
        val parts = buildSet { if (board == null) add(RefreshPart.SCOREBOARD); if (summary == null) add(RefreshPart.SUMMARY) }
        return Decision(parts, null)
    }

    private fun cadence(now: Long, board: Long?, boardEvery: Long, summary: Long?, summaryEvery: Long?): Decision {
        val fetch = mutableSetOf<RefreshPart>()
        val delays = mutableListOf<Long>()
        fun consider(part: RefreshPart, last: Long?, every: Long) {
            val remaining = if (last == null) 0L else (last + every - now).coerceAtLeast(0)
            if (remaining == 0L) { fetch += part; delays += every } else delays += remaining
        }
        consider(RefreshPart.SCOREBOARD, board, boardEvery)
        if (summaryEvery != null) consider(RefreshPart.SUMMARY, summary, summaryEvery)
        else if (summary == null) fetch += RefreshPart.SUMMARY
        return Decision(fetch, delays.minOrNull())
    }
}

/** Mobile-aligned planning API (`GameDetailRefreshPolicy.plan`): what to fetch now and when to look again. */
data class RefreshPlan(val fetchEvent: Boolean, val fetchSummary: Boolean, val nextCheckInMs: Long?) {
    val isIdle: Boolean get() = nextCheckInMs == null
}

private const val STALE_UPCOMING_MS = 3L * 60 * 60 * 1000
private const val MAX_BACKOFF_SHIFT = 3
private const val PRE_KICKOFF_LEAD_MS = 30L * 60 * 1000

/**
 * Same decision as [GameDetailRefreshPolicy.decide] in the shape the Mobile lane exposes: LIVE ticks the
 * event every liveMs and the summary every summaryMs; an UPCOMING game within 30 min of kick-off ticks the
 * event only; everything else is idle (zero requests, no next check). Failures back off 2x/4x/8x.
 */
fun GameDetailRefreshPolicy.plan(
    state: GameState,
    startsAtMs: Long,
    nowMs: Long,
    lastEventFetchMs: Long?,
    lastSummaryFetchMs: Long?,
    consecutiveFailures: Int = 0,
    config: GameDetailRefreshPolicy.Config = GameDetailRefreshPolicy.Config(),
    minMs: Long = 6_000L,
): RefreshPlan {
    val backoff = 1L shl consecutiveFailures.coerceIn(0, MAX_BACKOFF_SHIFT)
    val eventEvery = maxOf(config.liveMs, minMs) * backoff
    val summaryEvery = maxOf(config.summaryMs, minMs) * backoff
    fun remaining(last: Long?, every: Long) = if (last == null) 0L else (every - (nowMs - last)).coerceAtLeast(0L)
    val eventWait = remaining(lastEventFetchMs, eventEvery)
    val summaryWait = remaining(lastSummaryFetchMs, summaryEvery)
    return when (state) {
        GameState.FINAL, GameState.POSTPONED -> RefreshPlan(false, false, null)
        GameState.UPCOMING -> {
            val untilKickoff = startsAtMs - nowMs
            if (untilKickoff > PRE_KICKOFF_LEAD_MS || -untilKickoff > STALE_UPCOMING_MS) RefreshPlan(false, false, null)
            else {
                // Pre-kickoff the card only needs to notice the flip to live: the slow kickoff cadence, not liveMs.
                val wait = remaining(lastEventFetchMs, maxOf(config.kickoffPollMs, minMs) * backoff)
                RefreshPlan(wait == 0L, false, wait)
            }
        }
        GameState.LIVE -> RefreshPlan(eventWait == 0L, summaryWait == 0L, minOf(eventWait, summaryWait))
    }
}
