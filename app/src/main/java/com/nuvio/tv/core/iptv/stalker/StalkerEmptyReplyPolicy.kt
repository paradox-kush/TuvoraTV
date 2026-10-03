package com.nuvio.tv.core.iptv.stalker

import kotlinx.serialization.json.Json

/**
 * What to say when a portal call came back with nothing usable EVEN AFTER a fresh handshake.
 *
 * B02: every such reply used to read "the session is in use elsewhere". That is true for exactly one
 * shape — an Xtream-Codes-family panel whose token was taken by another box answers HTTP 200 with an
 * EMPTY body (`portal.php`: `if (!$continue) { CheckFlood(); die; }`). A reply that IS a JSON envelope
 * with an empty `js` means the portal understood us and has nothing for that call — typically a
 * section it does not run (genuine Ministra has no `type=series` module; series live under `vod`).
 * Anything else is an error page. Telling a viewer to hunt for a second device in those two cases
 * sends them the wrong way, and the support thread never learns what the portal actually said.
 *
 * Pure: classification from the raw body, plus a short REDACTED excerpt for the error-page case so
 * a screenshot carries the portal's own words without its MAC, token or credentials.
 */
internal object StalkerEmptyReplyPolicy {

    enum class Kind {
        /** Empty body: the token was not honoured — another device holds this MAC. Cools down. */
        HELD_ELSEWHERE,

        /** A well-formed envelope with an empty `js`: the portal has nothing for this call. */
        NOTHING_FOR_SECTION,

        /** Not JSON: an error/HTML page. */
        PORTAL_ERROR,
    }

    private val json = Json { isLenient = true }

    /** [body] is the retry's raw body; null or blank = an empty reply. */
    fun classify(body: String?): Kind {
        val text = body?.trim().orEmpty()
        if (text.isEmpty()) return Kind.HELD_ELSEWHERE
        val parses = (text.startsWith("{") || text.startsWith("[")) &&
            runCatching { json.parseToJsonElement(text) }.isSuccess
        return if (parses) Kind.NOTHING_FOR_SECTION else Kind.PORTAL_ERROR
    }

    /** Only the empty-body eviction should stop further re-handshakes (the two-device ping-pong). */
    fun startsCooldown(kind: Kind): Boolean = kind == Kind.HELD_ELSEWHERE

    fun message(kind: Kind, accountName: String, params: Map<String, String>, body: String?): String {
        val action = params["action"].orEmpty()
        return when (kind) {
            Kind.HELD_ELSEWHERE ->
                "Stalker portal returned no data for $action — the session is in use elsewhere " +
                    "(another box or app is probably using this MAC)"
            Kind.NOTHING_FOR_SECTION ->
                "$accountName returned nothing for ${sectionOf(params["type"])} ($action) — this portal " +
                    "may not offer it"
            Kind.PORTAL_ERROR ->
                "$accountName answered $action with an error page instead of data: \"${excerpt(body)}\""
        }
    }

    private fun sectionOf(type: String?): String = when (type) {
        "itv" -> "Live TV"
        "vod" -> "Movies"
        "series" -> "Series"
        else -> type ?: "this request"
    }

    /**
     * At most [max] characters of the portal's text: tags stripped, whitespace collapsed, and
     * anything identifying redacted — MAC addresses, long hex/base64-ish runs (tokens, device ids,
     * signatures) and `key=value` credentials.
     */
    fun excerpt(body: String?, max: Int = 80): String {
        val text = body.orEmpty()
            .replace(TAG, " ")
            .replace(MAC, "<mac>")
            .replace(CREDENTIAL, "$1=<redacted>")
            .replace(LONG_TOKEN, "<redacted>")
            .replace(WHITESPACE, " ")
            .trim()
        return if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"
    }

    private val TAG = Regex("<[^>]*>")
    private val MAC = Regex("(?i)\\b[0-9a-f]{2}(?:[:%3A-]+[0-9a-f]{2}){5}\\b")
    private val CREDENTIAL = Regex("(?i)\\b(token|mac|sn|password|passwd|pass|login|username|user|device_id2?|signature)=[^&\\s\"']*")
    private val LONG_TOKEN = Regex("[A-Za-z0-9+/_-]{24,}")
    private val WHITESPACE = Regex("\\s+")
}
