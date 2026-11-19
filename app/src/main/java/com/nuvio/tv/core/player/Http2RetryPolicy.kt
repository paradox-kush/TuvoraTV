package com.nuvio.tv.core.player

/**
 * Whether a request that came back 403 over HTTP/1.1 may be repeated once over HTTP/2 (upstream's
 * add-on `proxyHeaders` workaround).
 *
 * The repeat is a SECOND request for the same URL. For an IPTV / Stalker / own-source link that is a
 * second use of a single-use URL: the 403 there means "spent" or "expired", never "wrong HTTP
 * version", so repeating it can only burn the line's connection budget or re-trigger a mint loop
 * (see SingleUseLoadErrorHandlingPolicy, ExpiredLinkRefreshOwnership). Only a plain add-on / CDN
 * stream may take the retry. Pure: the host classes arrive as predicates so this tests without the
 * IPTV registry.
 */
object Http2RetryPolicy {
    fun allowed(
        isHttps: Boolean,
        host: String?,
        singleUseLink: Boolean,
        playlistDnsInUse: Boolean,
        isPanelHost: (String) -> Boolean,
        isMediaServerHost: (String) -> Boolean,
    ): Boolean {
        if (!isHttps) return false
        if (singleUseLink || playlistDnsInUse) return false
        val normalized = host?.trim()?.removePrefix("[")?.removeSuffix("]")?.trimEnd('.')?.lowercase()
            ?.takeIf { it.isNotEmpty() } ?: return false
        if (isPanelHost(normalized) || isMediaServerHost(normalized)) return false
        return true
    }
}
