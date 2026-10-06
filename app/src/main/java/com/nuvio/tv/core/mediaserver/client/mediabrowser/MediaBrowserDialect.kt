// Portions of this file are derived from Plezy (https://github.com/edde746/plezy), GPL-3.0,
// lib/media/media_browser_dialect.dart + lib/services/media_browser_paths.dart at commit
// 05ef93c55b0a6e8a35683762c2803e1756aaed1c, ported to Kotlin. Tuvora is GPL-3.0 as well.
package com.nuvio.tv.core.mediaserver.client.mediabrowser

import com.nuvio.tv.core.mediaserver.api.MediaServerType

/**
 * The pure table of what differs between the two MediaBrowser-family servers (design 5.2). Jellyfin forked
 * from Emby 3.5.2 and the wire contract is still ~95% shared, so ONE client serves both and every delta
 * lives here: routes ([MediaBrowserPaths]), token carriers, capability flags. No I/O, no state.
 *
 * Verified (this lane, against real servers - see research notes): Jellyfin 12.x accepts
 * `Authorization: MediaBrowser ... Token="..."` and `?ApiKey=`; the legacy `X-Emby-Token` / `?api_key=` /
 * `/emby/` forms are off by default there (admin flag). Emby documents `X-Emby-Token` + `?api_key=` and
 * accepts the `MediaBrowser` scheme in practice. Emby has no Quick Connect, no `/MediaSegments`, no
 * trickplay, and answers `GET /Shows/NextUp` only per series.
 */
internal enum class MediaBrowserDialect(val type: MediaServerType) {
    JELLYFIN(MediaServerType.JELLYFIN),
    EMBY(MediaServerType.EMBY);

    val productName: String get() = type.productName

    /** `/QuickConnect/...` + `POST /Users/AuthenticateWithQuickConnect` (Emby 404s every route). */
    val supportsQuickConnect: Boolean get() = this == JELLYFIN

    /** The query parameter that carries the token on a URL the SERVER builds or we must self-authenticate. */
    val tokenQueryParam: String get() = if (this == JELLYFIN) "ApiKey" else "api_key"

    /** The extra token header besides `Authorization: MediaBrowser ...` (Emby's documented carrier). */
    val extraTokenHeader: String? get() = if (this == EMBY) "X-Emby-Token" else null

    /** Only the pre-10.9 `/Users/{uid}/...` spelling of the user-scoped item routes (Emby). */
    val requiresUserScopedRoutes: Boolean get() = this == EMBY

    /** `AnyProviderIdEquals` on `/Items` works (Emby). Jellyfin has no provider-id filter (jellyfin#1990). */
    val supportsProviderIdFilter: Boolean get() = this == EMBY

    /** `GET /Shows/NextUp` answers a library-wide query (Jellyfin); Emby only computes it per series. */
    val supportsGlobalNextUp: Boolean get() = this == JELLYFIN

    /** The resume route returns ONLY started items (Jellyfin); Emby also mixes in one next-up episode per started series. */
    val resumeReturnsOnlyStartedItems: Boolean get() = this == JELLYFIN

    /** `/Sessions/Playing` and `/Progress` answer 400 without a `PlaySessionId` (Emby). */
    val requiresPlaySessionId: Boolean get() = this == EMBY

    val supportsTrickplay: Boolean get() = this == JELLYFIN
    val supportsMediaSegments: Boolean get() = this == JELLYFIN

    /** Ports a bare host is probed on, most likely first (both ship 8096 for http; Emby's https port is 8920). */
    val httpsPortGuesses: List<Int> get() = if (this == EMBY) listOf(8920, 8096) else listOf(8096)

    /** Row metadata Emby withholds from list responses unless named in `Fields` (measured on 4.9.5, re-verify on 4.10). */
    val extraRowFields: List<String>
        get() = if (this == EMBY) {
            listOf("ProductionYear", "OfficialRating", "PremiereDate", "DateCreated", "UserDataLastPlayedDate")
        } else {
            emptyList()
        }

    /** [fields] (a comma list) plus the row fields this dialect withholds, skipping any already named. */
    fun withRowFields(fields: String): String {
        val present = fields.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val missing = extraRowFields.filterNot { it in present }
        return if (missing.isEmpty()) fields else (fields.takeIf { it.isNotBlank() }?.let { "$it," } ?: "") + missing.joinToString(",")
    }

    companion object {
        fun of(type: MediaServerType): MediaBrowserDialect = if (type == MediaServerType.EMBY) EMBY else JELLYFIN

        /**
         * Best-effort dialect from a `/System/Info/Public` body: Jellyfin reports `ProductName` "Jellyfin
         * Server"; Emby 4.9 omits it but is the only one returning `RemoteAddresses`. Null when neither
         * signal is present, so the caller keeps the dialect the user picked.
         */
        fun detect(productName: String?, hasRemoteAddresses: Boolean): MediaBrowserDialect? {
            val name = productName?.lowercase().orEmpty()
            if (name.contains("jellyfin")) return JELLYFIN
            if (name.contains("emby")) return EMBY
            return if (hasRemoteAddresses) EMBY else null
        }
    }
}

/**
 * Route builders for the endpoints where the two dialects diverge. Jellyfin 10.9 renamed a batch of
 * user-scoped routes to unprefixed forms (`/Users/{uid}/PlayedItems/{id}` -> `/UserPlayedItems/{id}`) and
 * added `/Users/Me`; Emby only has the original spellings (`/Users/Me` answers 500 there - `Me` is bound as
 * a user id). Routes identical on both do not appear here.
 */
internal class MediaBrowserPaths(private val dialect: MediaBrowserDialect, private val userId: String) {
    private val user: String get() = "/Users/${segment(userId)}"

    /** The authenticated user's own DTO - health probe + revocation detection (an authenticated call; never streams/images, which are anonymous). */
    val currentUser: String get() = if (dialect.requiresUserScopedRoutes) user else "/Users/Me"

    /** Libraries (views). */
    val views: String get() = if (dialect.requiresUserScopedRoutes) "$user/Views" else "/UserViews"

    val resumeItems: String get() = if (dialect.requiresUserScopedRoutes) "$user/Items/Resume" else "/UserItems/Resume"

    /** One item: Jellyfin `/Items/{id}?userId=` (the legacy `/Users/{uid}/Items/{id}` still answers on 12.2 but is deprecated); Emby only has the user-scoped form. */
    fun item(itemId: String): String =
        if (dialect.requiresUserScopedRoutes) "$user/Items/${segment(itemId)}" else "/Items/${segment(itemId)}"

    /** Recently added: Jellyfin `/Items/Latest?userId=`; Emby 404s that and serves `/Users/{uid}/Items/Latest`. */
    val latest: String get() = if (dialect.requiresUserScopedRoutes) "$user/Items/Latest" else "/Items/Latest"

    /** Played flag (`POST` marks, `DELETE` unmarks). */
    fun playedItem(itemId: String): String =
        if (dialect.requiresUserScopedRoutes) "$user/PlayedItems/${segment(itemId)}" else "/UserPlayedItems/${segment(itemId)}"

    companion object {
        /** A path segment: unreserved characters stay, everything else is percent-encoded (ids are hex/numeric, but never trust the wire). */
        fun segment(value: String): String = percentEncode(value)
    }
}

/** RFC 3986 percent-encoding of one component (unreserved `A-Za-z0-9-_.~` plus `!*'()` kept, as `Uri.encodeComponent`). */
internal fun percentEncode(value: String): String {
    val keep = "-_.!~*'()"
    val sb = StringBuilder(value.length)
    for (b in value.encodeToByteArray()) {
        val c = (b.toInt() and 0xFF).toChar()
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in keep) {
            sb.append(c)
        } else {
            sb.append('%')
            val v = b.toInt() and 0xFF
            sb.append("0123456789ABCDEF"[v shr 4]).append("0123456789ABCDEF"[v and 0x0F])
        }
    }
    return sb.toString()
}
