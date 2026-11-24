package com.nuvio.tv.core.radar.alerts

import com.nuvio.tv.core.radar.RadarFollow
import com.nuvio.tv.core.radar.RadarTeamFollow

/**
 * Which Realtime topics this device should be joined to right now. Pure. One topic per sport
 * (`sports-alerts:v1:{slug}`), at most five, never anything per user.
 */
object SportsAlertTopicPolicy {
    const val TOPIC_PREFIX = "sports-alerts:v1:"

    /** The five sports the backend has a live feed for; other sports get no alerts in v1. */
    val SPORT_SLUGS: Set<String> = setOf("soccer", "american_football", "basketball", "baseball", "ice_hockey")

    /** "American Football" -> "american_football". */
    fun slugOf(sport: String?): String? =
        sport?.trim()?.lowercase()?.replace(Regex("[\\s-]+"), "_")?.takeIf { it in SPORT_SLUGS }

    fun topicOf(slug: String): String = TOPIC_PREFIX + slug

    /**
     * Sports of the non-muted follows, intersected with the five. Empty when alerts are off, nothing is followed, or
     * the user is signed out (Realtime runs only for signed-in users; this feature adds no sockets).
     */
    fun topics(
        settings: SportsAlertSettings,
        follows: List<RadarFollow>,
        teamFollows: List<RadarTeamFollow>,
        signedIn: Boolean,
    ): Set<String> {
        if (!signedIn || !settings.enabled) return emptySet()
        val leagueSports = follows.asSequence().filter { it.leagueId !in settings.mutedLeagues }.map { slugOf(it.sport) }
        val teamSports = teamFollows.asSequence().filter { it.teamId !in settings.mutedTeams }.map { slugOf(it.sport) }
        return (leagueSports + teamSports).filterNotNull().map(::topicOf).toSet()
    }
}
