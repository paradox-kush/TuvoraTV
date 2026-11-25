package com.nuvio.tv.core.telemetry.playback

/**
 * The privacy rules of the playback telemetry contract as a checker. PostHog's privacy layer redacts
 * properties named `code`, `state`, `url` or `token`, and nothing here may carry a URL, host, path,
 * channel / playlist / provider name, credential or message text, so every string value is one of a
 * closed set. Tests build each payload and run it through [violations] / [countKeyViolation].
 */
object PlaybackTelemetryPrivacy {
    /** Property names that must never be used (the redacted four, plus identifying words). */
    val FORBIDDEN_KEYS: Set<String> = setOf(
        "code", "state", "url", "token", "host", "user", "password", "mac", "key",
        "uri", "href", "path", "ip", "title", "name", "message", "cookie", "authorization", "secret",
    )

    private val forbiddenSuffixes = listOf("_url", "_uri", "_host", "_token", "_password", "_secret", "_cookie", "_path")

    private val kinds = PlayKind.entries.map { it.wire }.toSet()
    private val engines = PlayEngine.entries.map { it.wire }.toSet()

    /** String-valued properties of the contract, each with its closed set of values. */
    val ENUM_VALUES: Map<String, Set<String>> = mapOf(
        "kind" to kinds,
        "engine" to engines,
        "from_engine" to engines,
        "to_engine" to engines,
        "trigger" to FallbackTrigger.entries.map { it.wire }.toSet(),
        "outcome" to (StallOutcome.entries.map { it.wire } + TeardownOutcome.entries.map { it.wire }).toSet(),
        "event" to MultiviewEvent.entries.map { it.wire }.toSet(),
        "end_reason" to MultiviewEndReason.entries.map { it.wire }.toSet(),
        "duration_s_bucket" to setOf("lt30", "lt120", "lt600", "ge600"),
        "ext_class" to setOf("m3u8", "ts", "mp4", "mkv", "none", "other"),
        "content_type_class" to setOf("hls", "mp2t", "octet", "html", "text", "video", "other"),
        "source_type" to setOf("xtream", "stalker", "m3u", "file", "addon", "media_server"),
        "res_class" to setOf("sd", "hd720", "fhd", "uhd", "unknown"),
        "http_class" to setOf("none", "2xx", "3xx", "401_403", "404", "456", "4xx_other", "5xx"),
        "ram_class" to setOf("lt1_2", "lt2", "lt3", "ge3"),
        "prev_kind" to kinds,
        "prev_engine" to engines,
    )

    private val ttffBuckets = setOf("lt250", "lt500", "lt1000", "lt2000", "lt4000", "lt8000", "ge8000")
    private val stallBuckets = setOf("lt2000", "lt5000", "lt15000", "lt45000", "ge45000")

    /** Problems with a property payload; empty means it is safe to send. Numbers and booleans always pass. */
    fun violations(properties: Map<String, Any?>): List<String> {
        val out = ArrayList<String>()
        for ((key, value) in properties) {
            if (isForbiddenKey(key)) out += "forbidden key: $key"
            when (value) {
                null, is Number, is Boolean -> Unit
                is String -> {
                    val allowed = ENUM_VALUES[key]
                    if (allowed == null) out += "string property outside the contract: $key"
                    else if (value !in allowed) out += "value not in the enum for $key"
                }
                is Map<*, *> -> out += "nested map not allowed: $key"
                else -> out += "unsupported value type for $key"
            }
        }
        return out
    }

    fun isForbiddenKey(key: String): Boolean {
        val k = key.lowercase()
        return k in FORBIDDEN_KEYS || forbiddenSuffixes.any { k.endsWith(it) }
    }

    /** Null when [key] is a well-formed `session_health` count key of the contract, else the reason. */
    fun countKeyViolation(key: String): String? {
        val parts = key.split(':')
        val head = parts.first()
        fun bad(why: String) = "$why: $key"
        return when (head) {
            "ttff" -> when {
                parts.size != 4 -> bad("ttff needs 4 parts")
                parts[1] !in kinds || parts[2] !in engines || parts[3] !in ttffBuckets -> bad("ttff value outside enums")
                else -> null
            }
            "fallback" -> when {
                parts.size != 4 -> bad("fallback needs 4 parts")
                parts[1] !in engines || parts[2] !in engines ||
                    parts[3] !in FallbackTrigger.entries.map { it.wire } -> bad("fallback value outside enums")
                else -> null
            }
            "stall" -> when {
                parts.size != 3 -> bad("stall needs 3 parts")
                parts[1] !in kinds || parts[2] !in StallOutcome.entries.map { it.wire } -> bad("stall value outside enums")
                else -> null
            }
            "stall_ms" -> when {
                parts.size != 3 -> bad("stall_ms needs 3 parts")
                parts[1] !in kinds || parts[2] !in stallBuckets -> bad("stall_ms value outside enums")
                else -> null
            }
            "teardown" -> when {
                parts.size != 3 -> bad("teardown needs 3 parts")
                parts[1] !in engines || parts[2] !in TeardownOutcome.entries.map { it.wire } -> bad("teardown value outside enums")
                else -> null
            }
            "multiview" -> when {
                parts.size != 2 -> bad("multiview needs 2 parts")
                parts[1] !in MultiviewEvent.entries.map { it.wire } -> bad("multiview event outside enum")
                else -> null
            }
            "multiview_tiles_max" -> when {
                parts.size != 2 -> bad("multiview_tiles_max needs 2 parts")
                parts[1].toIntOrNull() !in PlaybackTelemetryKeys.MULTIVIEW_TILES_MIN..PlaybackTelemetryKeys.MULTIVIEW_TILES_MAX ->
                    bad("tiles outside 1..9")
                else -> null
            }
            else -> bad("unknown count key")
        }
    }
}
