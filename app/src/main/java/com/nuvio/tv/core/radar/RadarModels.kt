package com.nuvio.tv.core.radar

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Sports Centre (Radar) domain models. The catalog comes from [RadarCatalogData]; fixtures
 * come from our radar-fixtures Supabase edge function (TheSportsDB proxy + cache). KMP twin
 * of NuvioTV's core/radar models.
 */

// --- Catalog (bundled, curated) ----------------------------------------------

@Serializable
data class RadarCatalog(
    val version: Int = 1,
    val categories: List<RadarCategory> = emptyList(),
    val featured: List<RadarFeaturedEvent> = emptyList(),
)

@Serializable
data class RadarCategory(
    val name: String,
    val icon: String = "",
    val leagues: List<RadarLeague> = emptyList(),
)

@Serializable
data class RadarLeague(
    val id: String,
    val name: String,
    val sport: String? = null,
    val badge: String? = null,
    val banner: String? = null,
    /** Channel-matching keywords ("premier league", "epl", …) — data, not code. */
    val keywords: List<String> = emptyList(),
)

@Serializable
data class RadarFeaturedEvent(
    val id: String,
    val title: String,
    val leagueId: String,
    /** Inclusive date window, "YYYY-MM-DD" (UTC days). */
    val from: String,
    val to: String,
    val banner: String? = null,
    val badge: String? = null,
    val sport: String? = null,
) {
    fun isActive(nowMs: Long): Boolean {
        val fromMs = radarDateToEpochMs(from) ?: return false
        val toMs = radarDateToEpochMs(to)?.plus(DAY_MS) ?: return false
        return nowMs in fromMs until toMs
    }
}

// --- Fixtures (from the radar-fixtures edge function) -------------------------

@Serializable
data class RadarFixture(
    val id: String? = null,
    val leagueId: String? = null,
    val league: String? = null,
    val sport: String? = null,
    val home: String? = null,
    val away: String? = null,
    /** Full event name — the display fallback when home/away are absent (motorsport, golf…). */
    val event: String? = null,
    val homeBadge: String? = null,
    val awayBadge: String? = null,
    val leagueBadge: String? = null,
    /** "2026-07-02T14:00:00" (UTC, TheSportsDB strTimestamp). */
    val ts: String? = null,
    val round: String? = null,
    val venue: String? = null,
    val status: String? = null,
    val postponed: String? = null,
    val homeScore: String? = null,
    val awayScore: String? = null,
) {
    val startEpochMs: Long? get() = radarTimestampToEpochMs(ts)

    val displayTitle: String
        get() = when {
            !home.isNullOrBlank() && !away.isNullOrBlank() -> "$home  v  $away"
            !event.isNullOrBlank() -> event
            else -> league ?: ""
        }

    /** "Round of 32" style label from TheSportsDB's intRound conventions, or null. */
    val roundLabel: String?
        get() = when (round?.trim()) {
            null, "", "0" -> null
            "125" -> "Quarter-final"
            "150" -> "Semi-final"
            "160" -> "Third place"
            "200" -> "Final"
            "500" -> "Play-off"
            else -> "Round $round"
        }

    /** "2 – 1" when the API has a result for this fixture (finished/in-play), else null. */
    val scoreLabel: String?
        get() = if (!homeScore.isNullOrBlank() && !awayScore.isNullOrBlank()) "$homeScore – $awayScore" else null

    /**
     * In its live window? Kick-off reached and a sport-typical duration not yet elapsed.
     * The livescore feed overrides this for the sports it covers (soccer, NFL, NBA, MLB, NHL).
     */
    fun inferredLive(nowMs: Long): Boolean {
        if (postponed == "yes") return false
        val start = startEpochMs ?: return false
        return nowMs >= start && nowMs < start + typicalDurationMs(sport)
    }
}

@Serializable
data class RadarLiveScore(
    val eventId: String? = null,
    val status: String? = null,
    val progress: String? = null,
    val homeScore: String? = null,
    val awayScore: String? = null,
)

@Serializable
data class RadarFixturesResponse(
    val fixtures: Map<String, List<RadarFixture>> = emptyMap(),
    val livescore: Map<String, List<RadarLiveScore>> = emptyMap(),
    val fetchedAt: String? = null,
)

// --- Follows + prefs (persisted, synced) --------------------------------------

@Serializable
data class RadarFollow(
    @SerialName("league_id") val leagueId: String,
    val sport: String = "",
    @SerialName("sort_order") val sortOrder: Int = 0,
)

/** Opt-in state values mirror the backend enum-ish text column. */
object RadarOptIn {
    const val UNSET = "unset"
    const val ACCEPTED = "accepted"
    const val DECLINED = "declined"
}

@Serializable
data class RadarPrefs(
    @SerialName("featured_event_id") val featuredEventId: String = "",
    @SerialName("opt_in_state") val optInState: String = RadarOptIn.UNSET,
    @SerialName("promo_dismissed") val promoDismissed: Boolean = false,
)

/** The one locally-persisted blob (follows + prefs together; fixtures cache is separate). */
@Serializable
data class RadarLocalState(
    val follows: List<RadarFollow> = emptyList(),
    val prefs: RadarPrefs = RadarPrefs(),
)

// --- Time helpers (no kotlinx-datetime dependency; UTC civil-date math) --------

internal const val DAY_MS: Long = 24 * 60 * 60 * 1000L

/** Sports the TheSportsDB v2 livescore endpoint covers — everything else infers from time. */
val RADAR_LIVESCORE_SPORTS: Set<String> =
    setOf("soccer", "american football", "basketball", "baseball", "ice hockey")

private fun typicalDurationMs(sport: String?): Long {
    val hours = when (sport?.lowercase()) {
        "soccer" -> 2.5
        "basketball" -> 3.0
        "american football" -> 4.0
        "baseball" -> 4.0
        "ice hockey" -> 3.0
        "motorsport" -> 4.0
        "fighting" -> 6.0
        "rugby" -> 2.5
        "australian football" -> 3.0
        "cricket" -> 9.0
        // Day-long tournament sports: "live" most of the day is the honest answer.
        "tennis", "golf", "cycling" -> 10.0
        else -> 3.0
    }
    return (hours * 60 * 60 * 1000).toLong()
}

/** "YYYY-MM-DD" -> epoch ms at 00:00 UTC, or null. */
internal fun radarDateToEpochMs(date: String?): Long? {
    val parts = date?.trim()?.split("-") ?: return null
    if (parts.size != 3) return null
    val y = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    val d = parts[2].toIntOrNull() ?: return null
    return daysFromCivil(y, m, d) * DAY_MS
}

/** "2026-07-02T14:00:00" or "2026-07-02 14:00:00" (UTC) -> epoch ms, or null. */
internal fun radarTimestampToEpochMs(ts: String?): Long? {
    val t = ts?.trim()?.replace(' ', 'T') ?: return null
    val dateAndTime = t.split("T")
    val dayMs = radarDateToEpochMs(dateAndTime.getOrNull(0)) ?: return null
    val hms = dateAndTime.getOrNull(1)?.removeSuffix("Z")?.split(":") ?: return dayMs
    val h = hms.getOrNull(0)?.toIntOrNull() ?: 0
    val min = hms.getOrNull(1)?.toIntOrNull() ?: 0
    val s = hms.getOrNull(2)?.substringBefore('.')?.toIntOrNull() ?: 0
    return dayMs + ((h * 60L + min) * 60L + s) * 1000L
}

/** Howard Hinnant's days-from-civil: civil UTC date -> days since 1970-01-01. */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097L + doe - 719468L
}
