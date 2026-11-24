package com.nuvio.tv.core.radar.alerts

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * In-app sports alerts (lane A): the value types shared by the policy, the Realtime channel and the UI.
 * Twin of the Mobile/Desktop `features/radar/alerts` types; hand-ported (NuvioTV is its own codebase).
 */
enum class AlertKind(val wire: String) {
    Start("start"), Score("score"), Final("final");

    companion object {
        fun parse(raw: String?): AlertKind? = entries.firstOrNull { it.wire == raw }
    }
}

enum class AlertLevel(val wire: String) {
    All("all"), StartAndFinal("start_final"), FinalOnly("final"), Off("off");

    fun includes(kind: AlertKind): Boolean = when (this) {
        All -> true
        StartAndFinal -> kind != AlertKind.Score
        FinalOnly -> kind == AlertKind.Final
        Off -> false
    }

    companion object {
        /** An unknown value reads as [default] (like `sanitizeDelay`). */
        fun parse(raw: String?, default: AlertLevel): AlertLevel = entries.firstOrNull { it.wire == raw } ?: default

        /** Chip / row cycle: all -> start_final -> final -> off -> all. */
        fun next(current: AlertLevel): AlertLevel = entries[(entries.indexOf(current) + 1) % entries.size]
    }
}

enum class PlaybackContext { None, Vod, LiveTv }

enum class DuringPlayback(val wire: String) {
    Always("always"), LiveTvOnly("live"), Never("never");

    companion object {
        fun parse(raw: String?): DuringPlayback = entries.firstOrNull { it.wire == raw } ?: LiveTvOnly
        fun next(current: DuringPlayback): DuringPlayback = entries[(entries.indexOf(current) + 1) % entries.size]
    }
}

/** One moment from the backend (`sports-alerts:v1:{sport}` topic, event `alert`). Public sports data only. */
data class SportsAlert(
    val key: String,
    val eventId: String,
    val kind: AlertKind,
    val sport: String,
    val leagueId: String?,
    val league: String?,
    val homeId: String?,
    val awayId: String?,
    val home: String,
    val away: String,
    val homeScore: Int?,
    val awayScore: Int?,
    val progress: String?,
    val corrected: Boolean,
    /** Server epoch ms the moment was created. */
    val atMs: Long,
)

/** Tolerant decode of the broadcast payload: anything that does not fit the v1 contract is dropped, never thrown. */
object SportsAlertPayload {
    fun parse(obj: JsonObject, receivedAtMs: Long): SportsAlert? {
        fun str(name: String): String? = (obj[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        fun int(name: String): Int? = (obj[name] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }
        val version = int("v")
        if (version != null && version != 1) return null
        val key = str("key") ?: return null
        val eventId = str("eventId") ?: return null
        val kind = AlertKind.parse(str("kind")) ?: return null
        val sport = str("sport") ?: return null
        val home = str("home") ?: return null
        val away = str("away") ?: return null
        val atMs = (obj["at"] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: receivedAtMs
        return SportsAlert(
            key = key,
            eventId = eventId,
            kind = kind,
            sport = sport,
            leagueId = str("leagueId"),
            league = str("league"),
            homeId = str("homeId"),
            awayId = str("awayId"),
            home = home,
            away = away,
            homeScore = int("homeScore"),
            awayScore = int("awayScore"),
            progress = str("progress"),
            corrected = (obj["corrected"] as? JsonPrimitive)?.booleanOrNull ?: false,
            atMs = atMs,
        )
    }
}

/** What the banner shows. [eventId] null = the summary banner of several games. */
data class AlertBanner(
    val title: String,
    /** League and progress ("Premier League · 67'"); null when neither is known. */
    val detail: String?,
    /** Summary banner only: up to three one-line updates. */
    val lines: List<String> = emptyList(),
    val eventId: String? = null,
    val kind: AlertKind? = null,
    val sport: String? = null,
    /** How many games a summary banner stands for (1 for a normal banner). */
    val size: Int = 1,
) {
    val isSummary: Boolean get() = eventId == null
}

enum class DropReason(val wire: String) {
    Disabled("disabled"), Duplicate("duplicate"), Stale("stale"),
    NotFollowed("not_followed"), Muted("muted"), KindOff("kind_off"),
    WatchingThisGame("watching_this_game"), QuietHours("quiet_hours"), DuringPlayback("during_playback"),
    ScoresHidden("scores_hidden"), HourlyCap("hourly_cap"),
}

enum class HoldReason(val wire: String) { Delay("delay"), Batch("batch") }

sealed interface AlertDecision {
    data class ShowNow(val banner: AlertBanner) : AlertDecision
    data class HoldUntil(val atMs: Long, val reason: HoldReason) : AlertDecision
    data class Drop(val reason: DropReason) : AlertDecision
}

/** An alert waiting for its delay or its batch window. */
data class Held(val alert: SportsAlert, val dueAtMs: Long, val reason: HoldReason)

/** Per-device, in-memory bookkeeping: cap counters, the seen-key LRU, held alerts. Never persisted or synced. */
data class AlertLedger(
    val shownAtMs: List<Long> = emptyList(),
    val seenKeys: Set<String> = emptySet(),
    val pending: List<Held> = emptyList(),
    val lastBannerAtMs: Long? = null,
)

/** Everything the policy needs to know about "now" that is not a setting. */
data class AlertContext(
    val nowMs: Long,
    val localMinuteOfDay: Int,
    val playback: PlaybackContext,
    val openGameEventId: String?,
    val watchingEventId: String?,
    val followedLeagueIds: Set<String>,
    val followedTeamIds: Set<String>,
    val spoiler: com.nuvio.tv.core.radar.ScoreSpoilerSettings,
    /** Games revealed for this session while scores are hidden ([com.nuvio.tv.core.radar.RevealedGames]). */
    val revealed: Set<String>,
)
