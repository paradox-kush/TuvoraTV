package com.nuvio.tv.core.telemetry

/**
 * Removes exception MESSAGE text from events on their way out. A failed parse of a provider reply can
 * quote credentials or hostnames in its message, and PostHog's own exception autocapture sends that
 * text. Types and stack frames stay; the only message text kept is our own synthetic process-exit note.
 */
object ExceptionScrub {
    private const val SAFE_PREFIX = "Android process exited abnormally:"
    private val classLike = Regex("^[A-Za-z0-9_.$]{1,80}$")

    fun scrub(event: String, properties: Map<String, Any?>): Map<String, Any?> {
        var out = properties
        val list = properties["\$exception_list"]
        if (list is List<*>) {
            out = out + ("\$exception_list" to list.map { e ->
                if (e is Map<*, *>) {
                    val value = e["value"] as? String
                    if (value != null && !value.startsWith(SAFE_PREFIX)) e.filterKeys { it != "value" } else e
                } else e
            })
        }
        for (key in listOf("\$exception_values", "\$exception_message")) {
            val v = properties[key] ?: continue
            if (!v.toString().contains(SAFE_PREFIX)) out = out + (key to emptyList<String>())
        }
        if (event == "iptv_index_build" && properties.containsKey("detail")) {
            // `detail` is an exception's own message; keep only its class name.
            val className = (properties["detail"] as? String)?.substringBefore(':')?.trim()
            out = if (className != null && classLike.matches(className)) out + ("detail" to className) else out - "detail"
        }
        return out
    }
}
