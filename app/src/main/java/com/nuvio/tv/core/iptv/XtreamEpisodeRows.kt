package com.nuvio.tv.core.iptv

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.JsonPrimitive

/** Provider variants: season arrays, keyed episode maps and flat episode arrays. */
internal object XtreamEpisodeRows {
    data class Row(val id: String, val season: Int, val number: Int, val title: String,
                   val plot: String?, val still: String?, val extension: String?)

    fun parse(value: JsonElement?): List<Row> {
        val out = mutableListOf<Row>()
        fun visit(value: JsonElement?, season: Int?, number: Int?, depth: Int) {
            if (depth > 4) return
            when (value) {
                is JsonObject -> {
                    val id = value.text("id") ?: value.text("episode_id") ?: value.text("stream_id")
                    if (id != null) {
                        val info = value["info"] as? JsonObject
                        val n = value.integer("episode_num") ?: value.integer("episode_number")
                            ?: value.integer("episode") ?: number ?: 1
                        out += Row(id, value.integer("season") ?: value.integer("season_number") ?: season ?: 1,
                            n, value.text("title") ?: value.text("name") ?: "Episode $n",
                            info?.text("plot"), info?.text("movie_image"), value.text("container_extension"))
                    } else value.entrySet().forEach { (key, child) ->
                        val index = key.toIntOrNull()
                        visit(child, season ?: index, if (season != null) index else null, depth + 1)
                    }
                }
                is JsonArray -> value.forEachIndexed { index, child -> visit(child, season, index + 1, depth + 1) }
                else -> Unit
            }
        }
        visit(value, null, null, 0)
        return out.distinctBy { it.id }.sortedWith(compareBy({ it.season }, { it.number }))
    }
    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.asString?.trim()?.takeIf { it.isNotBlank() }
    private fun JsonObject.integer(key: String): Int? = text(key)?.toIntOrNull()
}
