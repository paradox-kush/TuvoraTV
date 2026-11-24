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
    /**
     * v1 contract: `v` must be 1, `at` (server epoch ms) is required, `away` is optional (a blank away is shown as the
     * home name alone). Anything else that does not fit is dropped, never thrown.
     */
    fun parse(obj: JsonObject): SportsAlert? {
        fun str(name: String): String? = (obj[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        fun int(name: String): Int? = (obj[name] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }
        if (int("v") != 1) return null
        val key = str("key") ?: return null
        val eventId = str("eventId") ?: return null
        val kind = AlertKind.parse(str("kind")) ?: return null
        val sport = str("sport") ?: return null
        val home = str("home") ?: return null
        val atMs = (obj["at"] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: return null
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
            away = str("away").orEmpty(),
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
    ScoresHidden("scores_hidden"), HourlyCap("hourly_cap"), WatchUnknown("watch_unknown"),
}

/** [WatchCheck]: live TV is playing and nobody knows yet whether it is this game; the lookup has until the hold's end. */
enum class HoldReason(val wire: String) { Delay("delay"), Batch("batch"), WatchCheck("watch_check") }

/** Whether the viewer's playing channel is this game's channel, as far as the cached lookup says. */
sealed interface WatchState {
    /** No lookup result for this game (never asked, expired, timed out). */
    data object Unknown : WatchState
    /** EVERY channel matched for the game (any confidence): the viewer is on it when any of them is playing. */
    data class Channel(val ids: Set<String>) : WatchState
    /** Looked up (cached): no channel, no playlist, nothing found. */
    data object NoChannel : WatchState
}

sealed interface AlertDecision {
    data class ShowNow(val banner: AlertBanner) : AlertDecision
    data class HoldUntil(val atMs: Long, val reason: HoldReason) : AlertDecision
    data class Drop(val reason: DropReason) : AlertDecision
}

/** An alert waiting for its delay or its batch window. */
data class Held(val alert: SportsAlert, val dueAtMs: Long, val reason: HoldReason, val receivedAtMs: Long)

/** Per-device, in-memory bookkeeping: cap counters, the seen-key LRU, held alerts. Never persisted or synced. */
data class AlertLedger(
    val shownAtMs: List<Long> = emptyList(),
    val seenKeys: Set<String> = emptySet(),
    val pending: List<Held> = emptyList(),
    val lastBannerAtMs: Long? = null,
    /** Count-only outcomes ("dropped:quiet_hours", "held:delay", ...). Never sent; for diagnostics and support code. */
    val counts: Map<String, Int> = emptyMap(),
)

/** Everything the policy needs to know about "now" that is not a setting. */
data class AlertContext(
    val nowMs: Long,
    val localMinuteOfDay: Int,
    val playback: PlaybackContext,
    val openGameEventId: String?,
    /** Every channel being played now (one for a player, all tiles for multiview). */
    val playingChannelIds: Set<String>,
    /** eventId -> cached lookup state; a game that is absent is [WatchState.Unknown]. */
    val watchStates: Map<String, WatchState>,
    val followedLeagueIds: Set<String>,
    val followedTeamIds: Set<String>,
    val spoiler: com.nuvio.tv.core.radar.ScoreSpoilerSettings,
    /** Games revealed for this session while scores are hidden ([com.nuvio.tv.core.radar.RevealedGames]). */
    val revealed: Set<String>,
)
