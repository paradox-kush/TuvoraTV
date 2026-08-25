package com.nuvio.tv.core.radar

/**
 * Pure name/programme scoring for the Sports Centre channel matcher, with the cross-sport guard.
 *
 * A single shared team word is cross-sport-risky: "Arizona Cardinals" (NFL) shares "Cardinals" with
 * "St. Louis Cardinals" (MLB) and "Arizona" with "Arizona Diamondbacks" (MLB), so a bare one-team hit
 * used to list those MLB channels under an NFL game. A one-team hit now counts only when the channel is
 * NOT advertising a competing league/sport marker (one this fixture's keywords don't include), and it
 * always ranks below a both-teams / league-keyword / event match.
 *
 * [matches] is the caller's word-boundary membership test over the already-normalised text.
 */
internal object SportsChannelMatchPolicy {

    /** League/sport abbreviations distinctive enough to flag a channel as a DIFFERENT competition. */
    private val LEAGUE_MARKERS = setOf(
        "nfl", "nba", "mlb", "nhl", "wnba", "ncaa", "mls", "cfl", "afl", "nrl", "ipl", "bbl",
    )

    private fun competingLeague(keywords: List<String>, matches: (String) -> Boolean): Boolean =
        LEAGUE_MARKERS.any { it !in keywords && matches(it) }

    /** Channel-NAME tier (cheap, in-memory). The old inline ladder + the one-team cross-sport guard. */
    fun nameScore(
        homeTokens: List<String>,
        awayTokens: List<String>,
        keywords: List<String>,
        eventTokens: List<String>,
        genericHit: Boolean,
        matches: (String) -> Boolean,
    ): Int {
        val homeHit = homeTokens.any(matches)
        val awayHit = awayTokens.any(matches)
        return when {
            homeHit && awayHit -> 50
            keywords.any(matches) -> 25
            eventTokens.count(matches) >= 2 -> 20
            (homeHit || awayHit) && !competingLeague(keywords, matches) -> 12
            genericHit -> 8
            else -> 0
        }
    }

    /** EPG-PROGRAMME-text tier, shared by panel short_epg and the canonical mirror. */
    fun programmeScore(
        homeTokens: List<String>,
        awayTokens: List<String>,
        keywords: List<String>,
        eventTokens: List<String>,
        matches: (String) -> Boolean,
    ): Int {
        val home = homeTokens.any(matches)
        val away = awayTokens.any(matches)
        val keyword = keywords.any(matches)
        return when {
            home && away -> 100
            eventTokens.count(matches) >= 2 -> 90
            (home || away) && keyword -> 70
            keyword -> 35
            (home || away) && !competingLeague(keywords, matches) -> 25
            else -> 0
        }
    }
}
