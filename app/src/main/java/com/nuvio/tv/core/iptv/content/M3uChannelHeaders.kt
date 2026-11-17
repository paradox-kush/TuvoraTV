package com.nuvio.tv.core.iptv.content

/**
 * Request headers one M3U channel asks for (`http-user-agent=` / `http-referrer=` attributes, `#EXTVLCOPT`,
 * `#EXTHTTP`). A small allow-list only — User-Agent, Referer, Origin; never Cookie. The values can identify
 * the provider or the subscriber, so they are never logged or sent to telemetry: [toString] prints
 * presence only.
 */
data class M3uChannelHeaders(
    val userAgent: String? = null,
    val referer: String? = null,
    val origin: String? = null,
) {
    /** Referer / Origin as request headers; the User-Agent travels in the request's own slot. */
    fun requestHeaders(): Map<String, String> = buildMap {
        referer?.let { put("Referer", it) }
        origin?.let { put("Origin", it) }
    }

    override fun toString(): String =
        "M3uChannelHeaders(userAgent=${userAgent != null}, referer=${referer != null}, origin=${origin != null})"
}
