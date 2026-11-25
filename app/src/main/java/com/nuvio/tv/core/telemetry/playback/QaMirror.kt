package com.nuvio.tv.core.telemetry.playback

/**
 * Local-only QA mirror. When the system property `debug.tuvora.qa` is `1` (read once at startup),
 * every playback telemetry signal is also written as ONE logcat line (tag [TAG]) with exact values, so
 * the QA lab can read numbers from a published build. Never networked, off by default, and a line that
 * would carry a forbidden key is dropped rather than written. Hand-built JSON: no org.json, so it
 * runs in plain JVM tests.
 */
class QaMirror(
    val enabled: Boolean,
    private val sink: (String) -> Unit,
) {
    /** Writes `{"ev":"<ev>", ...fields}` when enabled; a no-op (not even a string build) when off. */
    fun emit(ev: String, fields: Map<String, Any?>) {
        if (!enabled) return
        line(ev, fields)?.let { runCatching { sink(it) } }
    }

    companion object {
        const val TAG = "TuvoraQa"
        const val ANDROID_PROPERTY = "debug.tuvora.qa"
        const val JVM_PROPERTY = "tuvora.qa"

        val OFF = QaMirror(false) {}

        /** Only the literal `1` turns it on. */
        fun parseEnabled(raw: String?): Boolean = raw?.trim() == "1"

        /** The one-line JSON, or null when a field name is forbidden (the privacy rules hold for the mirror too). */
        fun line(ev: String, fields: Map<String, Any?>): String? {
            if (PlaybackTelemetryPrivacy.isForbiddenKey("ev") || fields.keys.any { PlaybackTelemetryPrivacy.isForbiddenKey(it) }) {
                return null
            }
            val sb = StringBuilder(64).append("{\"ev\":").append(quote(ev))
            for ((k, v) in fields) {
                sb.append(',').append(quote(k)).append(':')
                when (v) {
                    null -> sb.append("null")
                    is Number, is Boolean -> sb.append(v.toString())
                    else -> sb.append(quote(v.toString()))
                }
            }
            return sb.append('}').toString()
        }

        private fun quote(s: String): String {
            val sb = StringBuilder(s.length + 2).append('"')
            for (c in s) {
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c < ' ' -> sb.append(' ')
                    else -> sb.append(c)
                }
            }
            return sb.append('"').toString()
        }
    }
}
