package com.nuvio.tv.core.radar.alerts

import com.nuvio.tv.core.radar.RadarCatalog
import com.nuvio.tv.core.radar.RadarCatalogData
import kotlinx.serialization.json.Json

/** Plain-text labels for the alerts controls (the sports screens use literals, not string resources). Pure. */
object SportsAlertLabels {
    fun level(level: AlertLevel): String = when (level) {
        AlertLevel.All -> "Start, goals and full time"
        AlertLevel.StartAndFinal -> "Start and full time"
        AlertLevel.FinalOnly -> "Full time only"
        AlertLevel.Off -> "Off"
    }

    fun maxPerHour(v: Int): String = if (v <= 0) "No limit" else "$v an hour"

    fun batch(seconds: Int): String = when (seconds) {
        0 -> "Off"
        60 -> "1 minute"
        300 -> "5 minutes"
        else -> "${seconds}s"
    }

    fun duringPlayback(v: DuringPlayback): String = when (v) {
        DuringPlayback.Always -> "Always"
        DuringPlayback.LiveTvOnly -> "Live TV only"
        DuringPlayback.Never -> "Never"
    }

    /** 1320 -> "22:00". */
    fun minuteOfDay(minute: Int): String {
        val m = ((minute % 1440) + 1440) % 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /** The quiet-hours time rows step by one hour and wrap at midnight. */
    fun nextHour(minute: Int): Int = ((minute / 60 + 1) % 24) * 60

    /** Hub chip: "Alerts on" / "Alerts off". */
    fun hubChip(enabled: Boolean): String = if (enabled) "Alerts on" else "Alerts off"

    /** Game page chip: "Alerts: Default" / "Alerts: All" / "Alerts: Off". */
    fun gameChip(choice: GameAlertChoice): String = "Alerts: ${choice.label}"

    const val SIGN_IN_CHIP = "Sign in for alerts"
}

/** The bundled (no network) catalog's league names and sports, for follows that carry neither. */
object BundledLeagues {
    class Entry(val name: String, val sport: String?)

    private val byId: Map<String, Entry> by lazy {
        runCatching {
            Json { ignoreUnknownKeys = true }.decodeFromString<RadarCatalog>(RadarCatalogData.JSON)
                .categories.flatMap { it.leagues }.associate { it.id to Entry(it.name, it.sport) }
        }.getOrDefault(emptyMap())
    }

    fun sportOf(leagueId: String): String? = byId[leagueId]?.sport
    fun nameOf(leagueId: String): String? = byId[leagueId]?.name
}
