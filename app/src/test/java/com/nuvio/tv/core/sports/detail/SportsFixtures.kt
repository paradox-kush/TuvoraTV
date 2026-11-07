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

    fun gameDetail(name: String): String = raw("tsdb/$name.json")

    val mapping: SportsMapping by lazy { BundledSportsMapping.load() }
}
