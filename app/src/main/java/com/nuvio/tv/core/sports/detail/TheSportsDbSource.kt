package com.nuvio.tv.core.sports.detail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException

/**
 * TheSportsDB via our backend: `GET {functionsBase}/game-detail?event_id=&league_id=&blocks=` already
 * returns the NORMALISED game detail (header, state, live, blocks[]; nuvio-backend
 * supabase/functions/game-detail). This adapter only decodes it ([GameDetailWire]); there are no
 * TheSportsDB-shaped assemblers on the device. `disabled:true` (server kill switch) is honoured.
 *
 * SCOREBOARD-only ticks ask `blocks=none` (header/score/state only, a few hundred bytes); anything
 * with SUMMARY omits `blocks` so the server returns the sport's full block set (each on its own server cache).
 */
class TheSportsDbSource(
    private val http: HttpFetcher,
    private val functionsBase: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
) : BlockSource {
    override val id = SourceId.TSDB

    override suspend fun load(ref: GameRef, parts: Set<RefreshPart>, mapping: SportsMapping): SourceLoad {
        val eventId = ref.tsdbEventId ?: return SourceLoad.miss()
        val base = functionsBase()?.trimEnd('/') ?: return SourceLoad.miss()
        if (parts.isEmpty()) return SourceLoad.miss()
        val url = buildString {
            append(base).append('/').append(SportsDetailPaths.GAME_DETAIL).append("?event_id=").append(URLEncoder.encode(eventId, "UTF-8"))
            if (ref.leagueId.isNotBlank()) append("&league_id=").append(URLEncoder.encode(ref.leagueId, "UTF-8"))
            if (RefreshPart.SUMMARY !in parts) append("&blocks=none")
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
        return GameDetailWire.decode(root)
    }
}

/** Decoder for the backend's normalised GameDetail JSON. Forward compatible: unknown blocks/fields are ignored. Never throws. */
object GameDetailWire {
    private val r = JsonPathReader

    fun decode(root: JsonObject): SourceLoad = try {
        if (r.firstText(root, "disabled") == "true") SourceLoad.disabled() else {
            val header = header(root["header"])
            val blocks = r.read(root, "blocks[*]").mapNotNull { block(it) }.associateBy { it.type }
            val state = when (r.firstText(root, "state")) {
                "upcoming" -> GameState.UPCOMING; "live" -> GameState.LIVE; "final" -> GameState.FINAL; "postponed" -> GameState.POSTPONED
                else -> null
            }
            val live = root["live"]?.let { l ->
                val clock = r.firstText(l, "clock"); val detail = r.firstText(l, "detail")
                if (clock == null && detail == null) null else LiveInfo(clock = clock, detail = detail)
            }
            SourceLoad.ok(blocks, header, state, live)
        }
    } catch (_: Exception) {
        SourceLoad.failed(FetchOutcome.PARSE_FAIL)
    }

    private fun side(n: JsonElement?): Side? {
        val name = r.firstText(n, "name")?.takeIf { it.isNotBlank() } ?: return null
        return Side(name, short = r.firstText(n, "short"), badgeUrl = r.firstText(n, "badgeUrl"), score = r.firstText(n, "score"), record = r.firstText(n, "record"))
    }

    private fun header(h: JsonElement?): GameHeader? = when (r.firstText(h, "kind")) {
        "teams" -> side(r.first(h, "home"))?.let { hm -> side(r.first(h, "away"))?.let { GameHeader.Teams(hm, it) } }
        "versus" -> side(r.first(h, "a"))?.let { a -> side(r.first(h, "b"))?.let { GameHeader.Versus(a, it) } }
        "event" -> r.firstText(h, "name")?.takeIf { it.isNotBlank() }?.let { GameHeader.Event(it, r.firstText(h, "session"), r.firstText(h, "venue")) }
        else -> null
    }

    private fun sideKey(s: String?) = when (s) { "home" -> SideKey.HOME; "away" -> SideKey.AWAY; else -> null }

    private fun block(b: JsonElement): GameBlock? = try {
        val src = SourceId.fromWire(r.firstText(b, "source")) ?: SourceId.TSDB
        when (r.firstText(b, "type")) {
            "stats" -> r.read(b, "rows[*]").mapNotNull { n ->
                val label = r.firstText(n, "label") ?: return@mapNotNull null
                StatRow(label, r.firstText(n, "home") ?: "-", r.firstText(n, "away") ?: "-", r.firstText(n, "unit"))
            }.takeIf { it.isNotEmpty() }?.let { GameBlock.Stats(src, it) }
            "timeline" -> r.read(b, "items[*]").mapNotNull { n ->
                val text = r.firstText(n, "text") ?: return@mapNotNull null
                TimelineItem(r.firstText(n, "time").orEmpty(), r.firstText(n, "period"), sideKey(r.firstText(n, "side")), text, kind(r.firstText(n, "kind")))
            }.takeIf { it.isNotEmpty() }?.let { GameBlock.Timeline(src, it) }
            "lineup" -> r.read(b, "sides[*]").map { s ->
                LineupSide(r.firstText(s, "formation"), r.read(s, "players[*]").mapNotNull { p ->
                    r.firstText(p, "name")?.let { LineupPlayer(r.firstText(p, "number"), it, r.firstText(p, "pos"), r.firstText(p, "starter") != "false") }
                })
            }.takeIf { it.isNotEmpty() }?.let { GameBlock.Lineup(src, it) }
            "results" -> {
                val cols = r.read(b, "columns[*]").mapNotNull { r.text(it) }
                val rows = r.read(b, "rows[*]").map { row -> (row as? kotlinx.serialization.json.JsonArray)?.map { r.text(it).orEmpty() }.orEmpty() }
                if (rows.isEmpty()) null else GameBlock.Results(src, cols, rows)
            }
            "broadcast" -> r.read(b, "regions[*]").flatMap { reg ->
                val country = r.firstText(reg, "country")
                r.read(reg, "channels[*]").mapNotNull { r.text(it) }.map { BroadcastEntry(country, it) }
            }.takeIf { it.isNotEmpty() }?.let { GameBlock.Broadcast(src, it) }
            "highlights" -> r.firstText(b, "url")?.takeIf { it.isNotBlank() }?.let { GameBlock.Highlights(src, it, r.firstText(b, "thumbnail")) }
            "standings" -> r.read(b, "rows[*]").mapNotNull { n ->
                r.firstText(n, "name")?.let { StandingRow(r.firstText(n, "pos").orEmpty(), it, r.firstText(n, "played").orEmpty(), r.firstText(n, "points").orEmpty(), r.firstText(n, "gd"), r.firstText(n, "form")) }
            }.takeIf { it.isNotEmpty() }?.let { GameBlock.Standings(src, it) }
            "form" -> {
                val h = r.read(b, "home[*]").mapNotNull { r.text(it) }; val a = r.read(b, "away[*]").mapNotNull { r.text(it) }
                if (h.isEmpty() && a.isEmpty()) null else GameBlock.Form(src, h, a)
            }
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun kind(k: String?) = when (k) {
        "goal" -> TimelineKind.GOAL; "card" -> TimelineKind.CARD; "sub" -> TimelineKind.SUB
        "penalty" -> TimelineKind.PENALTY; "score" -> TimelineKind.SCORE; else -> null
    }
}
