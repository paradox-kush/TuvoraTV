package com.nuvio.tv.core.telemetry

import org.json.JSONArray
import org.json.JSONObject

/**
 * Decides what may enter the on-device log and how a trail is rendered for upload.
 *
 * Privacy by construction: only allow-listed field names survive, and a string value survives
 * only if it looks like an identifier (letters, digits, `_`, `-`, `:`), which rules out URLs,
 * hosts (no dots), paths, titles and any text with spaces. Numbers and booleans pass as-is.
 */
object DiagTrailPolicy {
    const val MAX_STRING = 40
    const val MAX_FIELDS = 12
    const val MAX_TRAIL_BYTES = 12 * 1024

    val ALLOWED_FIELDS: Set<String> = setOf(
        "engine", "phase", "outcome", "category", "failure_code", "failure_phase", "failure_domain",
        "kind", "surface", "source_type", "step", "reason", "retryability", "release_reason",
        "output_profile", "diagnostic_code", "network", "screen", "importance",
        "duration_ms", "item_count", "count", "attempt", "generation", "position_ms",
        "heap_used_mb", "heap_max_mb", "native_mb", "pss_mb", "free_storage_mb",
        "sdk_int", "width", "height", "fps", "bitrate_kbps",
    )

    private val identifier = Regex("^[A-Za-z0-9_:-]{1,$MAX_STRING}$")

    /** Keeps only safe fields. Never throws. */
    fun sanitize(fields: Map<String, Any?>): Map<String, Any> {
        if (fields.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Any>()
        for ((key, value) in fields) {
            if (out.size >= MAX_FIELDS) break
            if (key !in ALLOWED_FIELDS) continue
            when (value) {
                is Number, is Boolean -> out[key] = value
                is Enum<*> -> value.name.takeIf { identifier.matches(it) }?.let { out[key] = it }
                is String -> value.takeIf { identifier.matches(it) }?.let { out[key] = it }
                else -> Unit
            }
        }
        return out
    }

    private val invalidChars = Regex("[^A-Za-z0-9_:-]+")

    /**
     * A code is an identifier: other characters become `_` (a `$exception` event or a route pattern
     * keeps its shape) and the length is bounded. Free text cannot become a safe code on its own,
     * but codes only ever come from event names, enums and route patterns, never from user data.
     */
    fun safeCode(code: String): String =
        code.trim().replace(invalidChars, "_").trim('_').take(MAX_STRING).ifEmpty { "unknown" }

    /**
     * Renders the newest [maxEntries] entries oldest-first as `-1234ms area/code k=v k=v`, offsets
     * relative to the newest entry so no absolute clock is needed. Bounded to [MAX_TRAIL_BYTES]:
     * when over budget the OLDEST lines are dropped, because the lead-up to the error matters most.
     */
    fun render(entries: List<DiagEntry>, maxEntries: Int): String {
        if (entries.isEmpty() || maxEntries <= 0) return ""
        val tail = entries.takeLast(maxEntries)
        val newest = tail.last().atMs
        val lines = tail.map { e ->
            val offset = (e.atMs - newest).coerceAtMost(0)
            val fields = e.fields.entries.joinToString(" ") { (k, v) -> "$k=$v" }
            buildString {
                append(offset).append("ms ").append(e.area.wire).append('/').append(safeCode(e.code))
                if (fields.isNotEmpty()) append(' ').append(fields)
            }
        }
        var bytes = 0
        val kept = ArrayDeque<String>()
        for (line in lines.asReversed()) {
            val size = line.length + 1
            if (bytes + size > MAX_TRAIL_BYTES) break
            kept.addFirst(line)
            bytes += size
        }
        return kept.joinToString("\n")
    }

    fun toJson(entries: List<DiagEntry>): String {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject()
                    .put("t", e.atMs)
                    .put("a", e.area.name)
                    .put("c", e.code)
                    .put("f", JSONObject(e.fields)),
            )
        }
        return arr.toString()
    }

    fun fromJson(json: String): List<DiagEntry> = runCatching {
        val arr = JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val area = DiagArea.entries.firstOrNull { it.name == o.getString("a") } ?: continue
                val f = o.optJSONObject("f")
                val fields = buildMap<String, Any> {
                    f?.keys()?.forEach { k -> f.get(k).takeIf { it != JSONObject.NULL }?.let { put(k, it) } }
                }
                add(DiagEntry(o.getLong("t"), area, o.getString("c"), sanitize(fields)))
            }
        }
    }.getOrDefault(emptyList())
}
