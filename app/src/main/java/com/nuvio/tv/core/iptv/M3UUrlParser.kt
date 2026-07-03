package com.nuvio.tv.core.iptv

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Builds an [XtreamAccount] for an **M3U URL** playlist (sourceType = [XtreamAccount.SOURCE_URL]).
 * There's no Xtream API here — the whole playlist URL is the source, so:
 *  - [XtreamAccount.baseUrl] holds the FULL playlist URL verbatim (get.php / .m3u / .m3u8 link),
 *    NOT a stripped scheme+host (M3UClient fetches it as-is).
 *  - the optional User-Agent is stored in [XtreamAccount.username] (no dedicated field in the
 *    model; password stays empty). M3UClient reads it back as the request UA.
 *  - [XtreamAccount.id] is derived from the URL's scheme+host+port+path (query stripped) so it's
 *    stable across UA edits and distinct per playlist. Query params (creds) are excluded from the
 *    id to avoid leaking them into content ids while still separating different playlists on a host.
 *
 * Pure so the field->account mapping is unit-testable. Returns null on an unparseable URL.
 */
fun m3uAccountFromUrl(playlistUrl: String, userAgent: String? = null, name: String? = null): XtreamAccount? {
    val raw = playlistUrl.trim()
    if (raw.isEmpty()) return null
    val withScheme = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
    val url = withScheme.toHttpUrlOrNull() ?: return null
    val defaultPort = if (url.scheme == "https") 443 else 80
    val idBase = buildString {
        append(url.scheme).append("://").append(url.host)
        if (url.port != defaultPort) append(":").append(url.port)
        append(url.encodedPath)
    }
    return XtreamAccount(
        id = "m3u:$idBase",
        name = name?.trim()?.takeIf { it.isNotEmpty() } ?: url.host,
        baseUrl = withScheme,
        username = userAgent?.trim().orEmpty(),
        password = "",
        sourceType = XtreamAccount.SOURCE_URL
    )
}

/** True for an account whose content comes from a parsed M3U URL rather than an Xtream API. */
fun XtreamAccount.isM3U(): Boolean = sourceType == XtreamAccount.SOURCE_URL
