package com.nuvio.tv.core.radar

/**
 * Whether a channel match proves THIS fixture is on the channel (CONFIRMED — both teams named, the
 * specific event title, or a broadcaster listing) or only that the channel carries the competition
 * (LEAGUE — a league keyword or a single team word). The matcher threads this onto [ChannelMatch] so
 * the sheet can honestly say "Showing this match" vs "Carries <league>" instead of guessing from a score.
 */
enum class MatchConfidence { CONFIRMED, LEAGUE }

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

    /** A scored hit: how strongly it ranks, and whether it confirms the fixture or only the league. */
    data class Scored(val score: Int, val confidence: MatchConfidence)

    /** League/sport abbreviations distinctive enough to flag a channel as a DIFFERENT competition. */
    private val LEAGUE_MARKERS = setOf(
        "nfl", "nba", "mlb", "nhl", "wnba", "ncaa", "mls", "cfl", "afl", "nrl", "ipl", "bbl",
    )

    private fun competingLeague(keywords: List<String>, matches: (String) -> Boolean): Boolean =
        LEAGUE_MARKERS.any { it !in keywords && matches(it) }

    /**
     * Generic CLUB words shared across many teams ("Coventry City" / "Hull City" / "Man City" all carry
     * "city"). A shared one of these used to fake a both-teams match against any channel that merely
     * contained it ("New York City vs Seattle"). A team hit therefore requires a DISTINCTIVE token when
     * the team has one — sport words ("cricket", "football") are deliberately NOT here, so a dedicated
     * "Sky Sports Cricket" channel still surfaces for a cricket fixture.
     */
    private val GENERIC_TEAM_WORDS = setOf(
        "city", "united", "town", "rovers", "athletic", "real", "sporting", "county",
        "wanderers", "albion", "national", "team",
    )

    /** True when the channel text matches a DISTINCTIVE token of this team (falls back to any token if all are generic). */
    private fun teamHit(tokens: List<String>, matches: (String) -> Boolean): Boolean {
        val distinctive = tokens.filterNot { it in GENERIC_TEAM_WORDS }
        return if (distinctive.isNotEmpty()) distinctive.any(matches) else tokens.any(matches)
    }

    /** Channel-NAME tier (cheap, in-memory). The old inline ladder + the one-team cross-sport guard. */
    fun scoreName(
        homeTokens: List<String>,
        awayTokens: List<String>,
        keywords: List<String>,
        eventTokens: List<String>,
        genericHit: Boolean,
        matches: (String) -> Boolean,
    ): Scored {
        val homeHit = teamHit(homeTokens, matches)
        val awayHit = teamHit(awayTokens, matches)
        val keywordHit = keywords.any(matches)
        return when {
            homeHit && awayHit -> Scored(50, MatchConfidence.CONFIRMED)
            // This game's team on a league channel ("US NFL Tennessee Titans") ranks above a
            // bare league-keyword channel ("US NFL Buffalo Bills") — the wrong team's own feed.
            (homeHit || awayHit) && keywordHit -> Scored(30, MatchConfidence.LEAGUE)
            keywordHit -> Scored(25, MatchConfidence.LEAGUE)
            eventTokens.count(matches) >= 2 -> Scored(20, MatchConfidence.CONFIRMED)
            (homeHit || awayHit) && !competingLeague(keywords, matches) -> Scored(12, MatchConfidence.LEAGUE)
            genericHit -> Scored(8, MatchConfidence.LEAGUE)
            else -> Scored(0, MatchConfidence.LEAGUE)
        }
    }

    /** EPG-PROGRAMME-text tier, shared by panel short_epg and the canonical mirror. */
    fun scoreProgramme(
        homeTokens: List<String>,
        awayTokens: List<String>,
        keywords: List<String>,
        eventTokens: List<String>,
        matches: (String) -> Boolean,
    ): Scored {
        val home = teamHit(homeTokens, matches)
        val away = teamHit(awayTokens, matches)
        val keyword = keywords.any(matches)
        return when {
            home && away -> Scored(100, MatchConfidence.CONFIRMED)
            eventTokens.count(matches) >= 2 -> Scored(90, MatchConfidence.CONFIRMED)
            (home || away) && keyword -> Scored(70, MatchConfidence.LEAGUE)
            keyword -> Scored(35, MatchConfidence.LEAGUE)
            (home || away) && !competingLeague(keywords, matches) -> Scored(25, MatchConfidence.LEAGUE)
            else -> Scored(0, MatchConfidence.LEAGUE)
        }
    }

    /** Back-compat Int accessors (older callers/tests that only need the rank). */
    fun nameScore(
        homeTokens: List<String>,
        awayTokens: List<String>,
        keywords: List<String>,
        eventTokens: List<String>,
        genericHit: Boolean,
        matches: (String) -> Boolean,
    ): Int = scoreName(homeTokens, awayTokens, keywords, eventTokens, genericHit, matches).score

    fun programmeScore(
        homeTokens: List<String>,
        awayTokens: List<String>,
        keywords: List<String>,
        eventTokens: List<String>,
        matches: (String) -> Boolean,
    ): Int = scoreProgramme(homeTokens, awayTokens, keywords, eventTokens, matches).score

    /** The stronger of two confidences — CONFIRMED wins when merging signals for one channel. */
    fun stronger(a: MatchConfidence, b: MatchConfidence): MatchConfidence =
        if (a == MatchConfidence.CONFIRMED || b == MatchConfidence.CONFIRMED) MatchConfidence.CONFIRMED else MatchConfidence.LEAGUE
}
