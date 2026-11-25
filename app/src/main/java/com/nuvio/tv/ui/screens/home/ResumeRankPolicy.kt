package com.nuvio.tv.ui.screens.home

/**
 * "Pick up where I left off" ordering for Continue Watching, in the app and on the TV home screen.
 *
 * Pure: no clock, no I/O, no Android types. Deterministic: the same input gives the same order, and
 * equal scores order by [Candidate.key].
 *
 * `effectiveTime = base + boost`, sorted descending. Half-watched things float up (the viewer was in
 * the middle of them), next episodes follow, near-finished and barely-started items sink, and
 * episodes that have not aired yet always come last (nearest air date first).
 *
 * Time of day is deliberately not an input: there is no evidence it helps and it makes the order hard
 * to explain.
 *
 * Constants note: the spec table said Resume +24 h and barely-started -48 h, but its own cases 1
 * (half-watched film from yesterday beats a next-up from 2 h ago) and 4 (a 2% item from 3 days ago
 * sinks below a week-old 50% item) cannot hold with those numbers. They are +48 h and -72 h here, the
 * smallest change that satisfies every case; see ResumeRankPolicyTest.
 */
object ResumeRankPolicy {

    enum class Kind { Resume, NextUp, NewEpisode }

    /**
     * @property key stable identity, used for the tie-break and for mapping the result back.
     * @property lastEngagedAtMs when the viewer last watched this (for next-ups: the seed episode).
     * @property progressFraction 0..1 for [Kind.Resume]; null when unknown (and for next-ups).
     * @property releasedAtMs release instant of the episode, when known.
     * @property aired false for an episode that has not aired yet.
     */
    data class Candidate(
        val key: String,
        val kind: Kind,
        val lastEngagedAtMs: Long,
        val progressFraction: Double? = null,
        val releasedAtMs: Long? = null,
        val playedOnThisDevice: Boolean = false,
        val aired: Boolean = true
    )

    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 24 * HOUR_MS

    internal const val RESUME_BOOST_MS = 48 * HOUR_MS
    internal const val NEXT_UP_BOOST_MS = 12 * HOUR_MS
    internal const val NEW_EPISODE_BOOST_MS = 12 * HOUR_MS
    internal const val THIS_DEVICE_BOOST_MS = 6 * HOUR_MS
    internal const val NEAR_FINISHED_PENALTY_MS = 24 * HOUR_MS
    internal const val BARELY_STARTED_PENALTY_MS = 72 * HOUR_MS

    internal const val RESUME_MIN_FRACTION = 0.05
    internal const val RESUME_MAX_FRACTION = 0.90
    private const val NEXT_UP_WINDOW_MS = 7 * DAY_MS
    private const val NEW_EPISODE_WINDOW_MS = 7 * DAY_MS
    private const val STALE_AFTER_MS = 30 * DAY_MS
    private const val BARELY_STARTED_GRACE_MS = DAY_MS

    /** Candidate keys, best first. */
    fun rank(candidates: List<Candidate>, nowMs: Long): List<String> {
        if (candidates.isEmpty()) return emptyList()
        val (aired, unaired) = candidates.partition { it.aired }
        val airedKeys = aired
            .map { it to effectiveTimeMs(it, nowMs) }
            .sortedWith(
                compareByDescending<Pair<Candidate, Long>> { it.second }
                    .thenBy { it.first.key }
            )
            .map { it.first.key }
        val unairedKeys = unaired
            .sortedWith(
                compareBy<Candidate, Long?>(nullsLast()) { it.releasedAtMs }
                    .thenBy { it.key }
            )
            .map { it.key }
        return airedKeys + unairedKeys
    }

    /** Exposed for tests and for callers that need a stable synthetic engagement time. */
    internal fun effectiveTimeMs(candidate: Candidate, nowMs: Long): Long {
        val age = nowMs - candidate.lastEngagedAtMs
        val stale = age > STALE_AFTER_MS

        var base = candidate.lastEngagedAtMs
        if (candidate.kind == Kind.NewEpisode) {
            candidate.releasedAtMs?.let { base = maxOf(base, it) }
        }

        var boost = 0L
        when (candidate.kind) {
            Kind.Resume -> {
                val fraction = candidate.progressFraction
                if (fraction != null) {
                    when {
                        fraction > RESUME_MAX_FRACTION -> boost -= NEAR_FINISHED_PENALTY_MS
                        fraction < RESUME_MIN_FRACTION ->
                            if (age > BARELY_STARTED_GRACE_MS) boost -= BARELY_STARTED_PENALTY_MS
                        !stale -> boost += RESUME_BOOST_MS
                    }
                }
            }
            Kind.NextUp -> if (!stale && age <= NEXT_UP_WINDOW_MS) boost += NEXT_UP_BOOST_MS
            Kind.NewEpisode -> {
                val released = candidate.releasedAtMs
                if (!stale && released != null && released <= nowMs && nowMs - released <= NEW_EPISODE_WINDOW_MS) {
                    boost += NEW_EPISODE_BOOST_MS
                }
            }
        }
        if (!stale && candidate.playedOnThisDevice) boost += THIS_DEVICE_BOOST_MS
        return base + boost
    }
}
