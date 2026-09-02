package com.nuvio.tv.core.iptv

/** A viewer-facing hint for a non-2xx status the *player* got while fetching a stream. */
enum class StreamHttpStatusHint {
    /** No specific hint — the raw code + ExoPlayer error name is all we honestly have. */
    NONE,

    /** 400/403: the source refused or restricted the stream. */
    BLOCKED,

    /** 401/410: the tokenized link has expired. */
    EXPIRED,

    /** 404: the stream is gone from the panel. */
    REMOVED,

    /** 429: too many requests, back off. */
    RATE_LIMITED,

    /** 5xx: the origin is unwell. */
    UNAVAILABLE,

    /**
     * A provider firewall / anti-bot edge turned the stream request away with a non-standard,
     * usually body-less code (456 seen live on a Cloudflare-fronted panel). Distinct from [BLOCKED]
     * because the remedy is specific and actionable: pin an honest IPTV-client User-Agent on the
     * playlist (see [StreamUserAgentPolicy]) rather than "try a different source".
     */
    PROVIDER_FIREWALL,
}

/**
 * Maps the HTTP status the *stream* fetch received to a [StreamHttpStatusHint]. Pure and
 * Context-free so it unit-tests without Android; the player controller owns the string resources.
 *
 * Sibling of [IptvLoadFailurePolicy], which classifies *catalog/portal-load* failures — this one is
 * for the media fetch (ExoPlayer's `ERROR_CODE_IO_BAD_HTTP_STATUS`). 456 lands here as
 * [StreamHttpStatusHint.PROVIDER_FIREWALL] instead of falling through to a raw, unexplained code.
 */
object StreamHttpStatusPolicy {
    fun hint(code: Int): StreamHttpStatusHint = when (code) {
        400, 403 -> StreamHttpStatusHint.BLOCKED
        401, 410 -> StreamHttpStatusHint.EXPIRED
        404 -> StreamHttpStatusHint.REMOVED
        429 -> StreamHttpStatusHint.RATE_LIMITED
        456 -> StreamHttpStatusHint.PROVIDER_FIREWALL
        500, 502, 503, 504 -> StreamHttpStatusHint.UNAVAILABLE
        else -> StreamHttpStatusHint.NONE
    }
}
