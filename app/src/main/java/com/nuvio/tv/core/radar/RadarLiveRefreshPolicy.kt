package com.nuvio.tv.core.radar

/**
 * Decides which followed leagues/teams a fast "live tick" must refresh, so the Sports screen's
 * 2-minute score poll re-downloads only fixtures that could plausibly be live now — not the whole
 * followed set.
 *
 * Why this exists: the poll used to call `refreshFixtures(force = true)` every 2 minutes, which
 * refetches every followed league in full (~26 KB each — for a 62-league follower that is ~850 KB)
 * purely to pick up live scores that total a few KB. That single behaviour was the top source of
 * Supabase egress (one heavy follower re-pulling ~850 KB every 2 minutes, 24/7). This narrows the
 * tick to leagues/teams with a fixture inside its live-or-imminent window and returns nothing when
 * the slate is idle, so the poll makes no network call at all. Schedule discovery stays on the slow
 * full refresh.
 *
 * Pure: candidate ids + fixtures + now in, target ids out — no repository, network or player, so it
 * unit-tests without any of them.
 */
object RadarLiveRefreshPolicy {

    /**
     * How early before kick-off a fixture's league joins the live tick, so a pre-game card gains
     * its score the moment the game starts instead of waiting out the slow full-refresh interval.
     */
    const val PRE_KICKOFF_LEAD_MS: Long = 30L * 60 * 1000

    data class Targets(val leagueIds: Set<String>, val teamIds: Set<String>) {
        val isEmpty: Boolean get() = leagueIds.isEmpty() && teamIds.isEmpty()
    }

    /**
     * @param candidateLeagueIds the leagues the screen is actually showing (followed + active
     *   featured) — a live fixture cached for a merely-browsed league must not pull it back in.
     */
    fun targets(
        candidateLeagueIds: Set<String>,
        candidateTeamIds: Set<String>,
        fixturesByLeague: Map<String, List<RadarFixture>>,
        fixturesByTeam: Map<String, List<RadarFixture>>,
        nowMs: Long,
        leadMs: Long = PRE_KICKOFF_LEAD_MS,
    ): Targets {
        val leagues = candidateLeagueIds
            .filter { id -> fixturesByLeague[id].orEmpty().any { it.isLiveOrImminent(nowMs, leadMs) } }
            .toSet()
        val teams = candidateTeamIds
            .filter { id -> fixturesByTeam[id].orEmpty().any { it.isLiveOrImminent(nowMs, leadMs) } }
            .toSet()
        return Targets(leagues, teams)
    }

    /**
     * A fixture worth a fast refresh: not already finished/postponed, kick-off within [leadMs] of
     * now, and still inside its sport's plausible live window. Feed-independent on purpose — the
     * tick runs BEFORE we have fresh feed data, so it decides on the clock alone (the same window
     * math [RadarFixture.maxLiveWindowMs] uses), and over-including by a few minutes is harmless.
     */
    private fun RadarFixture.isLiveOrImminent(nowMs: Long, leadMs: Long): Boolean {
        if (isFinishedOrOff) return false
        val start = startEpochMs ?: return false
        return nowMs >= start - leadMs && nowMs < start + maxLiveWindowMs()
    }
}
