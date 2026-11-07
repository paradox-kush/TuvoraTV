package com.nuvio.tv.core.sports.detail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * ESPN adapter (device-side only: ESPN's CDN blocks server IPs). Finds the ESPN event for the game
 * (override table, else scoreboard + [EventMatcher], cached per game), then assembles blocks through
 * the mapping. A live tick (SCOREBOARD only) never touches the summary endpoint.
 * Failure is reported as an outcome, never as an empty result.
 */
class EspnSource(
    private val http: HttpFetcher,
    private val clock: () -> Long = System::currentTimeMillis,
) : BlockSource {
    override val id = SourceId.ESPN

    private val matchedIds = ConcurrentHashMap<String, String>()
    private val boardCache = ConcurrentHashMap<String, Pair<JsonElement, Long>>()
    private val dayFormat = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

    private sealed interface Fetched {
        data class Doc(val json: JsonElement) : Fetched
        data class NotFound(val code: Int) : Fetched
        data class Failed(val outcome: FetchOutcome) : Fetched
    }

    private suspend fun fetch(url: String, deadlineMs: Long): Fetched = try {
        val r = http.get(url, mapOf("Accept" to "application/json"), deadlineMs)
        when {
            r.code == 404 -> Fetched.NotFound(404)
            !r.isSuccessful -> Fetched.Failed(FetchOutcome.HTTP_FAIL)
            else -> try {
                Fetched.Doc(Json.parseToJsonElement(r.body.orEmpty()) as? kotlinx.serialization.json.JsonObject ?: return Fetched.Failed(FetchOutcome.PARSE_FAIL))
            } catch (_: Exception) {
                Fetched.Failed(FetchOutcome.PARSE_FAIL)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        Fetched.Failed(FetchOutcome.HTTP_FAIL)
    } catch (_: Exception) {
        Fetched.Failed(FetchOutcome.HTTP_FAIL)
    }

    override suspend fun load(ref: GameRef, parts: Set<RefreshPart>, mapping: SportsMapping): SourceLoad {
        val league = mapping.leagueFor(ref.leagueId) ?: return SourceLoad.miss()
        val path = league.espnPath ?: return SourceLoad.miss()
        val profileName = mapping.profileNameFor(ref.leagueId, ref.sport) ?: return SourceLoad.miss()
        val profile = mapping.profile(profileName) ?: return SourceLoad.miss()
        if (parts.isEmpty()) return SourceLoad.miss()
        val asm = EspnBlockAssembler(mapping)
        val deadline = mapping.deadlineMs

        var eventId = (mapping.eventOverrides[ref.rawId] ?: mapping.eventOverrides[ref.gameId]) ?: ref.espnEventId ?: matchedIds[ref.gameId]
        var event: JsonElement? = null
        var failure: FetchOutcome? = null
        val hasSummary = league.summary && profile.specFromSummary()

        // Delta rule: the whole-day scoreboard is fetched ONLY to resolve the event id (then cached per game).
        // A leagues without a per-game summary (F1, UFC, tennis) has no cheaper endpoint, so it keeps using it.
        // Leagues without a summary (F1, UFC, tennis) have nothing cheaper, so the scoreboard is re-read at the slow
        // (summary) cadence and reused in between.
        val cachedBoard = if (!hasSummary && eventId != null) boardCache[ref.gameId]?.takeIf { clock() - it.second < mapping.refresh.summaryMs } else null
        if (cachedBoard != null) event = cachedBoard.first
        else if (eventId == null || !hasSummary) {
            when (val found = scoreboard(ref, path, eventId, mapping, deadline)) {
                is Board.Failed -> failure = found.outcome
                is Board.Found -> { eventId = found.id; event = found.event; matchedIds[ref.gameId] = found.id; boardCache[ref.gameId] = found.event to clock() }
                Board.None -> Unit
            }
        }
        if (eventId == null) return SourceLoad(failure ?: FetchOutcome.EMPTY)

        var summary: JsonElement? = null
        if (hasSummary) {
            // One per-game summary serves both the live tick (header/competitions carry score + clock) and the slower blocks.
            when (val s = fetch(mapping.espnSummaryUrl.replace("{path}", path).replace("{id}", eventId), deadline)) {
                is Fetched.Doc -> summary = s.json
                is Fetched.Failed -> failure = failure ?: s.outcome
                is Fetched.NotFound -> Unit
            }
            if (event == null) event = eventFromSummary(summary)
        }

        val docs = EspnDocs(event, summary)
        val blocks = asm.all(profileName, docs)
        val header = asm.header(profileName, docs)
        if (blocks.isEmpty() && header == null) return SourceLoad(failure ?: FetchOutcome.EMPTY)
        return SourceLoad.ok(blocks, header, asm.gameState(docs), asm.live(docs, profileName))
    }

    /**
     * A scoreboard-shaped event built from the summary's `header.competitions[0]`, so the same mapping
     * paths (competitors, status, linescores) read it. Blocks only the scoreboard carries (leaders, form,
     * broadcast) are absent on ticks and keep their earlier value through GameDetail.mergeOver.
     */
    private fun eventFromSummary(summary: JsonElement?): JsonElement? {
        val comp = JsonPathReader.first(summary, "header.competitions[0]") as? kotlinx.serialization.json.JsonObject ?: return null
        val situation = JsonPathReader.first(summary, "situation")
        val withSituation = if (situation != null && !comp.containsKey("situation")) kotlinx.serialization.json.JsonObject(comp + ("situation" to situation)) else comp
        val fields = LinkedHashMap<String, JsonElement>()
        JsonPathReader.first(summary, "header.id")?.let { fields["id"] = it }
        comp["status"]?.let { fields["status"] = it }
        fields["competitions"] = kotlinx.serialization.json.JsonArray(listOf(withSituation))
        return kotlinx.serialization.json.JsonObject(fields)
    }

    private fun SportProfile.specFromSummary(): Boolean = blocks.any { b ->
        (spec(b)?.get("from") as? kotlinx.serialization.json.JsonPrimitive)?.content == "summary"
    }

    private sealed interface Board {
        data class Found(val id: String, val event: JsonElement) : Board
        data class Failed(val outcome: FetchOutcome) : Board
        data object None : Board
    }

    /** Today's scoreboard, then the day before (late local starts are listed under the previous ESPN date). */
    private suspend fun scoreboard(ref: GameRef, path: String, knownId: String?, mapping: SportsMapping, deadline: Long): Board {
        val days = listOf(ref.startsAtMs, ref.startsAtMs - 12 * 3_600_000L).map { dayFormat.format(Instant.ofEpochMilli(it)) }.distinct()
        val q = MatchQuery(ref.gameId, ref.homeName, ref.awayName, ref.startsAtMs, ref.eventName)
        for (day in days) {
            val url = mapping.espnScoreboardUrl.replace("{path}", path).replace("{yyyymmdd}", day)
            when (val f = fetch(url, deadline)) {
                is Fetched.Failed -> return Board.Failed(f.outcome)
                is Fetched.NotFound -> continue
                is Fetched.Doc -> {
                    val events = JsonPathReader.read(f.json, mapping.commonPath("event") ?: "events[*]")
                    val idPath = mapping.commonPath("id") ?: "id"
                    val id = knownId ?: EventMatcher.match(q, EventMatcher.candidatesFromScoreboard(f.json, mapping), mapping)
                    val ev = id?.let { wanted -> events.firstOrNull { JsonPathReader.firstText(it, idPath) == wanted } }
                    if (id != null && ev != null) return Board.Found(id, ev)
                }
            }
        }
        return Board.None
    }
}
