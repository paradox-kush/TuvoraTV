package com.nuvio.tv.playback.core

/**
 * The ONE header-assembly rule both engine plans consume. Header shape is shared pure logic:
 * both engines must send byte-identical headers for the identical request, or a fix applied to
 * one lane silently diverges the wire behavior the compatibility history keys on.
 *
 * User-Agent stays per-plan (each engine decides how a UA reaches the wire); everything else —
 * case-insensitive replacement, Referer/Origin promotion, cookie serialization — lives here.
 */
fun PlaybackRequest.assembledHttpHeaders(): LinkedHashMap<String, String> {
    val assembled = linkedMapOf<String, String>()
    headers.forEach { (name, value) -> assembled.putReplacingCaseInsensitive(name, value) }
    referer?.let { assembled.putReplacingCaseInsensitive("Referer", it) }
    origin?.let { assembled.putReplacingCaseInsensitive("Origin", it) }
    if (cookies.isNotEmpty()) {
        assembled.putReplacingCaseInsensitive(
            "Cookie",
            cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" },
        )
    }
    return assembled
}

fun MutableMap<String, String>.putReplacingCaseInsensitive(name: String, value: String) {
    keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(::remove)
    put(name, value)
}
