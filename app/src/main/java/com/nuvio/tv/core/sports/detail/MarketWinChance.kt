package com.nuvio.tv.core.sports.detail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/**
 * Polymarket win chance via OUR backend (devices never call Polymarket):
 * `GET {functionsBase}/market-winchance?game_id=&league_id=&home=&away=&starts_at=[&history=1]` →
 * `{gameId, home, away, draw?, asOf, source:"polymarket", attribution:"via Polymarket", storylines:[{key,label,yes,side?}], series?}`
 * | `{disabled:true}` | 404 / `{error}` (no market) | 429 / 502 (failure).
 *
 * Only asked when SUMMARY is among the parts (the slow cadence — never its own timer) and only when the mapping's
 * `killSwitch.market` switched it on for the game ([GameDetailResolver] checks that). No odds, prices or links are ever read.
 */
class MarketWinChanceSource(
    private val http: HttpFetcher,
    private val functionsBase: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
) : BlockSource {
    override val id = SourceId.MARKET

    override fun wants(parts: Set<RefreshPart>): Boolean = RefreshPart.SUMMARY in parts

    override suspend fun load(ref: GameRef, parts: Set<RefreshPart>, mapping: SportsMapping): SourceLoad {
        val base = functionsBase()?.trimEnd('/') ?: return SourceLoad.miss()
        val url = buildString {
            append(base).append('/').append(SportsDetailPaths.MARKET_WIN_CHANCE)
            append("?game_id=").append(enc(ref.gameId))
            append("&league_id=").append(enc(ref.leagueId))
            append("&home=").append(enc(ref.homeName))
            append("&away=").append(enc(ref.awayName))
            append("&starts_at=").append(enc(Instant.ofEpochMilli(ref.startsAtMs).toString()))
            // The series feeds only the live "comeback watch": not asked for a game that is not about to start.
            if (clock() >= ref.startsAtMs - HISTORY_LEAD_MS) append("&history=1")
        }
        val resp = try {
            http.get(url, mapOf("Accept" to "application/json"), mapping.deadlineMs)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return SourceLoad.failed(FetchOutcome.HTTP_FAIL)
        }
        if (resp.code == 404) return SourceLoad.miss()
        if (!resp.isSuccessful) return SourceLoad.failed(FetchOutcome.HTTP_FAIL)
        val root = try {
            Json.parseToJsonElement(resp.body.orEmpty()) as? JsonObject ?: return SourceLoad.failed(FetchOutcome.PARSE_FAIL)
        } catch (_: Exception) {
            return SourceLoad.failed(FetchOutcome.PARSE_FAIL)
        }
        return decode(root)
    }

    private fun decode(o: JsonObject): SourceLoad {
        if ((o["disabled"] as? JsonPrimitive)?.booleanOrNull == true) return SourceLoad.disabled()
        if (o["error"] != null && o["home"] == null) return SourceLoad.miss()
        val home = pct(o["home"]) ?: return SourceLoad.failed(FetchOutcome.PARSE_FAIL)
        val draw = pct(o["draw"])
        val away = pct(o["away"]) ?: (100 - home - (draw ?: 0)).coerceIn(0, 100)
        // `history=1` answers with the current price AND `series` (older backends named it `history`).
        val points = ((o["series"] ?: o["history"]) as? JsonArray).orEmpty().mapNotNull { p ->
            val po = p as? JsonObject ?: return@mapNotNull null
            val t = (po["t"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val h = pct(po["home"]) ?: return@mapNotNull null
            WinPoint(t, h, pct(po["draw"]))
        }
        val block = GameBlock.WinProbability(
            source = SourceId.MARKET,
            homePercent = home.toDouble(), awayPercent = away.toDouble(), drawPercent = draw?.toDouble(),
            attribution = (o["attribution"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "via Polymarket",
            history = points, storylines = storylines(o["storylines"]),
            asOfMs = (o["asOf"] as? JsonPrimitive)?.longOrNull ?: clock(),
        )
        return SourceLoad.ok(mapOf(BlockType.WIN_PROBABILITY to block)).copy(asked = setOf(BlockType.WIN_PROBABILITY))
    }

    /** Blank labels and rows without a number are dropped, then at most [MAX_STORYLINES] kept. */
    private fun storylines(e: JsonElement?): List<Storyline> = (e as? JsonArray).orEmpty().mapIndexedNotNull { i, row ->
        val o = row as? JsonObject ?: return@mapIndexedNotNull null
        val label = (o["label"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
        val yes = pct(o["yes"]) ?: return@mapIndexedNotNull null
        val side = when ((o["side"] as? JsonPrimitive)?.takeIf { it.isString }?.content) { "home" -> SideKey.HOME; "away" -> SideKey.AWAY; else -> null }
        Storyline(key = (o["key"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "s$i", label = label, yes = yes, side = side)
    }.take(MAX_STORYLINES)

    private fun pct(e: JsonElement?): Int? {
        val d = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return null
        return d.roundToInt().coerceIn(0, 100)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private companion object {
        const val MAX_STORYLINES = 3
        const val HISTORY_LEAD_MS = 15 * 60_000L
    }
}

/**
 * Pure rules for the market extras on the win-chance card (shared with NuvioMobile's `GameDetailUiPolicy`): which storylines
 * are still meaningful for the game's state, the client-derived "comeback watch", the card's own staleness and its caption.
 */
object MarketPolicy {
    private const val COMEBACK_WINDOW_MS = 10L * 60 * 1000
    private const val COMEBACK_MIN_RISE = 15
    private const val COMEBACK_MIN_POINTS = 3
    private const val SERIES_FRESH_MS = 2L * 60 * 1000
    private const val STALE_AFTER_MS = 120_000L

    data class Comeback(val side: SideKey, val rise: Int)

    /**
     * "Comeback watch". Only for a LIVE game, with a series whose last point is within ~2 min of the server's [asOfMs], at least
     * three points inside the last 10 minutes, and the rise measured from the START of that window to its end (a spike that came
     * back down is nothing). Who is "trailing" comes from the headline price, not from the series' last point; the series is
     * home's per-point non-draw share, so away's rise is home's fall.
     */
    fun comebackWatch(history: List<WinPoint>, headlineHome: Int, headlineAway: Int, state: GameState, asOfMs: Long?): Comeback? {
        if (state != GameState.LIVE || asOfMs == null || history.size < COMEBACK_MIN_POINTS) return null
        val pts = history.sortedBy { it.t }
        val last = pts.last()
        if (asOfMs - last.t > SERIES_FRESH_MS) return null
        val window = pts.filter { it.t >= last.t - COMEBACK_WINDOW_MS }
        if (window.size < COMEBACK_MIN_POINTS) return null
        val start = window.first().home
        val end = last.home
        val (side, rise) = when {
            headlineHome < headlineAway -> SideKey.HOME to (end - start)
            headlineAway < headlineHome -> SideKey.AWAY to (start - end)
            else -> return null
        }
        return if (rise >= COMEBACK_MIN_RISE) Comeback(side, rise) else null
    }

    private fun halfBoundary(sport: String?): Int? = when (sport?.lowercase()) { "soccer" -> 1; "basketball", "american football" -> 2; else -> null }
    private fun regulationPeriods(sport: String?): Int? = when (sport?.lowercase()) { "basketball", "american football" -> 4; "ice hockey" -> 3; else -> null }

    /** The period number in a label such as "Q2", "2nd", "2H" (null when it has none: "OT", "Halftime"). */
    fun periodNumber(live: LiveInfo?): Int? = Regex("\\d+").find(live?.periodLabel.orEmpty())?.value?.toIntOrNull()

    /** Storylines only before and during the game; first-half ones vanish after half-time, overtime ones once overtime has started. */
    fun visibleStorylines(storylines: List<Storyline>, state: GameState, live: LiveInfo?, sport: String?): List<Storyline> {
        if (state != GameState.UPCOMING && state != GameState.LIVE) return emptyList()
        val period = if (state == GameState.LIVE) periodNumber(live) else null
        val regulation = regulationPeriods(sport)
        val label = live?.periodLabel.orEmpty()
        val inOvertime = state == GameState.LIVE && (label.contains("OT", ignoreCase = true) || (period != null && regulation != null && period > regulation))
        val half = halfBoundary(sport)
        return storylines.filter { s ->
            val key = s.key.lowercase()
            when {
                "first_half" in key -> period == null || half == null || period <= half
                "overtime" in key -> !inOvertime
                else -> true
            }
        }
    }

    /** "Goes to overtime · 8%" / "Leading at half-time: DEN · 62%" (a one-sided storyline carries its team tag). */
    fun storylineText(s: Storyline, homeTag: String?, awayTag: String?): String {
        val label = s.label.trim()
        val tag = when (s.side) { SideKey.HOME -> homeTag; SideKey.AWAY -> awayTag; null -> null }?.takeIf { it.isNotBlank() }
        val named = if (tag != null && !label.contains(tag, ignoreCase = true)) "$label: $tag" else label
        return "$named · ${s.yes.coerceIn(0, 100)}%"
    }

    fun comebackText(c: Comeback, homeTag: String?, awayTag: String?): String? =
        (if (c.side == SideKey.HOME) homeTag else awayTag)?.takeIf { it.isNotBlank() }?.let { "Comeback watch · $it +${c.rise} in 10 min" }

    /** The card chip: only for a LIVE game and only when the MARKET is the headline, once its server time is over 2 minutes old. */
    fun staleLabel(b: GameBlock.WinProbability, state: GameState, nowMs: Long): String? {
        if (state != GameState.LIVE || b.source != SourceId.MARKET) return null
        val age = nowMs - (b.asOfMs ?: return null)
        if (age <= STALE_AFTER_MS) return null
        val minutes = age / 60_000
        return if (minutes < 60) "Updated $minutes min ago" else "Updated ${minutes / 60} h ago"
    }

    /** "via Polymarket" / "via ESPN": the block's attribution, else derived from its source. */
    fun caption(b: GameBlock.WinProbability): String? = b.attribution?.takeIf { it.isNotBlank() } ?: when (b.source) {
        SourceId.ESPN -> "via ESPN"
        SourceId.MARKET -> "via Polymarket"
        else -> null
    }
}
