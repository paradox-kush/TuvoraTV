package com.nuvio.tv.core.mediaserver.client.mediabrowser

/**
 * URL builders for the routes a PLAYER or image loader fetches (everything else is an API call made by the
 * client with headers). Token hygiene (design 5.5): tokens travel in headers; the only token that may sit
 * in a URL is one the SERVER built into it (`TranscodingUrl` carries `ApiKey=`/`api_key=` itself). Jellyfin's
 * direct stream and BOTH products' images need no token (verified against Jellyfin 12.2 and Emby 4.10: image
 * routes answer anonymously); Emby's direct stream authenticates by header (`X-Emby-Token`), Jellyfin's needs none.
 */
internal object MediaBrowserUrls {
    /** `{base}/Videos/{id}/stream?Static=true&MediaSourceId=..` - the original file, no transcoder. No token, ever. */
    fun directStream(
        baseUrl: String,
        itemId: String,
        mediaSourceId: String?,
        container: String? = null,
        playSessionId: String? = null,
    ): String {
        val params = buildList {
            add("Static" to "true")
            mediaSourceId?.trim()?.takeIf { it.isNotEmpty() }?.let { add("MediaSourceId" to it) }
            container?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Container" to it) }
            playSessionId?.trim()?.takeIf { it.isNotEmpty() }?.let { add("PlaySessionId" to it) }
        }
        return "${base(baseUrl)}/Videos/${percentEncode(itemId)}/stream?${query(params)}"
    }

    /**
     * A sidecar subtitle file: `{base}/Videos/{id}/{mediaSourceId}/Subtitles/{index}/0/Stream.{format}` - the server converts any
     * text subtitle to the requested [format]. Tokenless (Jellyfin serves it anonymously, like images); Emby's token rides the
     * player's request headers, never this URL.
     */
    fun subtitle(baseUrl: String, itemId: String, mediaSourceId: String, index: Int, format: String = "srt"): String =
        "${base(baseUrl)}/Videos/${percentEncode(itemId)}/${percentEncode(mediaSourceId)}/Subtitles/$index/0/Stream.${percentEncode(format)}"

    /** Resolves a server-relative path (`/videos/..`, `/Videos/..`) or an absolute URL against [baseUrl]; the server's own query (incl. its ApiKey) is kept verbatim. */
    fun resolve(baseUrl: String, pathOrUrl: String): String {
        val value = pathOrUrl.trim()
        if (value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)) return value
        return base(baseUrl) + (if (value.startsWith("/")) value else "/$value")
    }

    /** An image URL for [itemId]: always credential-free (it ends up in Coil's disk cache journal). [tag] busts the cache when the artwork changes. */
    fun image(
        baseUrl: String,
        itemId: String,
        imageType: String = "Primary",
        tag: String? = null,
        maxWidth: Int? = null,
        quality: Int? = 90,
    ): String {
        val params = buildList {
            tag?.trim()?.takeIf { it.isNotEmpty() }?.let { add("tag" to it) }
            maxWidth?.let { add("maxWidth" to it.toString()) }
            quality?.let { add("quality" to it.toString()) }
        }
        val q = if (params.isEmpty()) "" else "?${query(params)}"
        return "${base(baseUrl)}/Items/${percentEncode(itemId)}/Images/${percentEncode(imageType)}$q"
    }

    private fun base(baseUrl: String): String = baseUrl.trim().trimEnd('/')

    private fun query(params: List<Pair<String, String>>): String =
        params.joinToString("&") { (k, v) -> "$k=${percentEncode(v)}" }
}
