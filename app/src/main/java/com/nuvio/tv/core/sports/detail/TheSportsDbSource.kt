package com.nuvio.tv.core.sports.detail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException

/**
 * TheSportsDB adapter, served by our backend (cached; the paid key never ships in the app).
 *
 * ASSUMED CONTRACT (the backend `game-detail` function is wave 3 and not built yet; align when it is):
 *   GET {functionsBase}/game-detail?event_id=<idEvent>&parts=<event|stats,timeline,tv,highlights,lineup,results>
 *   -> { "event": {events:[..]} | {..}, "stats": {lookup:[..]}, "timeline": .., "tv": .., "highlights": .., "lineup": .., "results": .. }
 * where each member is TheSportsDB's own response body. SCOREBOARD maps to `event`, SUMMARY to the rest.
 */
class TheSportsDbSource(
    private val http: HttpFetcher,
    private val functionsBase: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
) : BlockSource {
    override val id = SourceId.TSDB
    private val assembler = TsdbBlockAssembler()

    override suspend fun load(ref: GameRef, parts: Set<RefreshPart>, mapping: SportsMapping): SourceLoad {
        if (!ref.gameId.startsWith("tsdb:")) return SourceLoad.miss()
        val base = functionsBase()?.trimEnd('/') ?: return SourceLoad.miss()
        val want = buildList {
            if (RefreshPart.SCOREBOARD in parts) add("event")
            if (RefreshPart.SUMMARY in parts) addAll(listOf("stats", "timeline", "tv", "highlights", "lineup", "results"))
        }
        if (want.isEmpty()) return SourceLoad.miss()
        val url = "$base/game-detail?event_id=${URLEncoder.encode(ref.rawId, "UTF-8")}&parts=${want.joinToString(",")}"
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
        val docs = TsdbDocs(
            event = eventObject(root["event"]),
            stats = root["stats"], timeline = root["timeline"], tv = root["tv"],
            highlights = root["highlights"], lineup = root["lineup"], results = root["results"],
        )
        return SourceLoad.ok(assembler.all(docs), assembler.header(docs), assembler.state(docs).takeIf { docs.event != null }, assembler.live(docs))
    }

    private fun eventObject(e: JsonElement?): JsonObject? =
        JsonPathReader.first(e, "events[0]") as? JsonObject
            ?: JsonPathReader.first(e, "lookup[0]") as? JsonObject
            ?: (e as? JsonObject)?.takeIf { it.containsKey("idEvent") || it.containsKey("strEvent") }
}
