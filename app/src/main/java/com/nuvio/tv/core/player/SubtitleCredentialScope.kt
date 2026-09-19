package com.nuvio.tv.core.player

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Decides which of the playing stream's request headers may ride along with a subtitle download,
 * and when forwarded credentials must be dropped on a redirect. Pure so it unit-tests without
 * OkHttp/Android.
 *
 * Threat model: stream request headers can carry a credential under any name (Authorization, Cookie,
 * a debrid/portal token under a custom header). Forwarding them to a foreign subtitle host — directly,
 * via a cross-host redirect, or over an https->http downgrade — leaks that credential. Subtitle URLs
 * that genuinely need auth on their own host carry it in their own URL, not the stream's headers.
 */
internal object SubtitleCredentialScope {
    // Hop-by-hop / addressing headers must never be forwarded — they break foreign or CDN edges.
    private val NON_FORWARDABLE = setOf("range", "host", "connection", "transfer-encoding")

    /**
     * The stream headers that may be attached to the INITIAL subtitle request. Non-empty only when
     * the subtitle shares the stream's host AND the hop does not downgrade https->http. Returns empty
     * when either URL is not http(s), when hosts differ, or on a downgrade — i.e. fail closed.
     */
    fun forwardableStreamHeaders(
        streamUrl: String?,
        subtitleUrl: String,
        streamHeaders: Map<String, String>,
    ): Map<String, String> {
        val subtitle = subtitleUrl.toHttpUrlOrNull() ?: return emptyMap()
        val stream = streamUrl?.toHttpUrlOrNull() ?: return emptyMap()
        if (!subtitle.host.equals(stream.host, ignoreCase = true)) return emptyMap()
        if (stream.isHttps && !subtitle.isHttps) return emptyMap()
        return streamHeaders.filterKeys { it.trim().lowercase() !in NON_FORWARDABLE }
    }

    /**
     * True when [hopUrl] (a redirect target) leaves the original subtitle host, so any forwarded
     * stream credentials must be stripped before the request goes out. OkHttp only drops Authorization
     * on a cross-host redirect; Cookie and custom-named credentials would otherwise survive the hop.
     * An unparseable origin or hop is treated as leaving (fail closed).
     */
    fun redirectLeavesOriginHost(originSubtitleUrl: String, hopUrl: String): Boolean {
        val origin = originSubtitleUrl.toHttpUrlOrNull() ?: return true
        val hop = hopUrl.toHttpUrlOrNull() ?: return true
        return !hop.host.equals(origin.host, ignoreCase = true)
    }
}
