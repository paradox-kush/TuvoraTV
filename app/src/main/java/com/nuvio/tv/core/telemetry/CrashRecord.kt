package com.nuvio.tv.core.telemetry

import org.json.JSONArray
import org.json.JSONObject

/**
 * What survives a Java crash: written synchronously by [CrashRecorder] in the dying process,
 * sent once on the next launch. Android's ApplicationExitInfo carries no trace for Java crashes,
 * so this is the only place the stack lives.
 */
data class CrashRecord(
    val crashId: String,
    val crashedAtMs: Long,
    val appVersion: String,
    val thread: String,
    val exceptionType: String,
    val message: String?,
    val frames: List<String>,
) {
    fun toJson(): String = JSONObject()
        .put("schema_version", SCHEMA_VERSION)
        .put("crash_id", crashId)
        .put("crashed_at_ms", crashedAtMs)
        .put("app_version", appVersion)
        .put("thread", thread)
        .put("exception_type", exceptionType)
        .put("message", message ?: JSONObject.NULL)
        .put("frames", JSONArray(frames))
        .toString()

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_FRAMES = 64
        const val MAX_MESSAGE = 300

        fun fromJson(json: String): CrashRecord? = runCatching {
            val o = JSONObject(json)
            if (o.optInt("schema_version", -1) != SCHEMA_VERSION) return null
            val arr = o.getJSONArray("frames")
            CrashRecord(
                crashId = o.getString("crash_id"),
                crashedAtMs = o.getLong("crashed_at_ms"),
                appVersion = o.getString("app_version"),
                thread = o.getString("thread"),
                exceptionType = o.getString("exception_type"),
                message = if (o.isNull("message")) null else o.getString("message"),
                frames = List(arr.length()) { arr.getString(it) },
            )
        }.getOrNull()

        /** Flattens the cause chain into at most [MAX_FRAMES] lines, causes marked like a JVM trace. */
        fun framesOf(t: Throwable): List<String> {
            val out = ArrayList<String>(MAX_FRAMES)
            var current: Throwable? = t
            var depth = 0
            val seen = HashSet<Throwable>()
            while (current != null && seen.add(current) && out.size < MAX_FRAMES && depth < 8) {
                if (depth > 0) out += "Caused by: ${current.javaClass.name}"
                for (e in current.stackTrace) {
                    if (out.size >= MAX_FRAMES) break
                    out += "${e.className}.${e.methodName}(${e.fileName}:${e.lineNumber})"
                }
                current = current.cause
                depth++
            }
            return out
        }
    }
}
