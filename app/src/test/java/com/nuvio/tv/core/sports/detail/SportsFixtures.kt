package com.nuvio.tv.core.sports.detail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray

/** Trimmed copies of research/sports-fixtures (ESPN + TheSportsDB), 2026-11-07. */
object SportsFixtures {
    fun raw(path: String): String =
        checkNotNull(SportsFixtures::class.java.classLoader!!.getResourceAsStream("sports/$path")) { "missing fixture $path" }
            .bufferedReader().use { it.readText() }

    fun json(path: String): JsonElement = Json.parseToJsonElement(raw(path))
    fun espnEvent(k: String) = json("espn/${k}_event.json")
    fun espnSummary(k: String) = json("espn/${k}_summary_post.json")
    fun espnDocs(k: String, summary: Boolean = true) =
        EspnDocs(espnEvent(k), if (summary) runCatching { espnSummary(k) }.getOrNull() else null)

    fun tsdbEvent(k: String): JsonObject = json("tsdb/${k}_event.json").jsonObject["events"]!!.jsonArray[0].jsonObject
    fun tsdbLookup(name: String): JsonElement = json("tsdb/$name.json")

    fun tsdbDocsEpl() = TsdbDocs(
        event = tsdbEvent("epl"),
        stats = tsdbLookup("ev_lookup_event_stats_2494097"),
        timeline = tsdbLookup("ev_lookup_event_timeline_2494097"),
        tv = tsdbLookup("ev_lookup_event_tv_2494097"),
        highlights = tsdbLookup("ev_lookup_event_highlights_2494097"),
    )

    val mapping: SportsMapping by lazy { BundledSportsMapping.load() }
}
