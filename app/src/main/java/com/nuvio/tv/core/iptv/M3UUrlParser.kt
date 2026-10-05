package com.nuvio.tv.core.iptv

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Builds an [XtreamAccount] for an **M3U URL** playlist (sourceType = [XtreamAccount.SOURCE_URL]).
 * There's no Xtream API here — the whole playlist URL is the source, so:
 *  - [XtreamAccount.baseUrl] holds the FULL playlist URL verbatim (get.php / .m3u / .m3u8 link),
 *    NOT a stripped scheme+host (M3UClient fetches it as-is).
 *  - the optional User-Agent is stored in [XtreamAccount.username] (no dedicated field in the
 *    model; password stays empty). M3UClient reads it back as the request UA.
 *  - [XtreamAccount.id] is the shared Step 0 key `m3u|<url minus login>` ([PlaylistKey.m3uUrl], B64)
 *    — the same id a phone or the web mints for the same link. (TV used to derive `m3u:` + scheme/host/port/path
 *    with the query stripped; that form survives only as [tvLegacyM3uId], the pull fallback for a
 *    server row without a key. An edit never re-derives the id — the caller keeps the old one.)
 *
 * Pure so the field->account mapping is unit-testable. Returns null on an unparseable URL.
 */
fun m3uAccountFromUrl(playlistUrl: String, userAgent: String? = null, name: String? = null): XtreamAccount? {
    val raw = playlistUrl.trim()
    if (raw.isEmpty()) return null
    val withScheme = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
    val url = withScheme.toHttpUrlOrNull() ?: return null
    return XtreamAccount(
        id = PlaylistKey.m3uUrl(raw) ?: return null,
        name = name?.trim()?.takeIf { it.isNotEmpty() } ?: url.host,
        baseUrl = withScheme,
        username = userAgent?.trim().orEmpty(),
        password = "",
        sourceType = XtreamAccount.SOURCE_URL
    )
}

/**
 * TV's pre-Step-0 M3U id: `m3u:` + scheme://host[:port] + path, query stripped. Only the pull's
 * fallback for a server row that carries no `playlist_key` yet (today's derivation, per the Step 0
 * contract) — never minted for a new playlist.
 */
fun tvLegacyM3uId(playlistUrl: String): String? {
    val raw = playlistUrl.trim()
    if (raw.isEmpty()) return null
    val withScheme = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
    val url = withScheme.toHttpUrlOrNull() ?: return null
    val defaultPort = if (url.scheme == "https") 443 else 80
    return buildString {
        append("m3u:").append(url.scheme).append("://").append(url.host)
        if (url.port != defaultPort) append(":").append(url.port)
        append(url.encodedPath)
    }
}

/**
 * The Xtream panel behind an M3U-URL paste, when the URL is the panel's own `get.php` export or its
 * `player_api.php` root carrying `username` + `password`; null for any other URL (a plain `.m3u` is
 * not a panel). The M3U lane hard-codes every channel as no-archive, so saving such a paste as an
 * M3U playlist silently strips catch-up from a perfectly good account — the ADD flow consults this
 * first. Twin of NuvioMobile's recogniseXtreamPanelInM3uField.
 *
 * Deliberately NOT folded into [m3uAccountFromUrl]: that builder also runs on EDIT, pairing and
 * sync, where an existing `m3u:…` playlist must keep its identity — re-keying it would orphan every
 * favourite and progress entry saved under the old id. Recognition is an add-time offer only.
 */
fun xtreamPanelInM3uUrl(playlistUrl: String, userAgent: String? = null, name: String? = null): XtreamAccount? {
    val raw = playlistUrl.trim()
    if (raw.isEmpty()) return null
    val withScheme = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
    val url = withScheme.toHttpUrlOrNull() ?: return null
    val path = url.encodedPath.lowercase()
    if (!(path.endsWith("/get.php") || path.endsWith("/player_api.php"))) return null
    val parsed = parseXtreamAccount(withScheme, name) ?: return null
    return parsed.copy(userAgent = userAgent?.trim()?.takeIf { it.isNotEmpty() })
}

/**
 * Builds an [XtreamAccount] for an **M3U FILE** playlist (sourceType = [XtreamAccount.SOURCE_FILE]).
 * The picked file is copied into app storage (see M3UFileStore) so the original can disappear; the
 * account only carries a stable [playlistId] and the [fileName] for display + re-import prompts.
 *
 *  - [XtreamAccount.id] is the supplied [playlistId] (minted once by [newM3UFilePlaylistId] when the
 *    user picks a file, so re-editing the same playlist keeps its id and its saved content ids).
 *  - [XtreamAccount.baseUrl] is empty — there is no URL; the source is the local copy at
 *    `files/playlists/{id-hash}.m3u`. [XtreamAccount.username] (the M3U UA slot) stays empty too.
 *  - [XtreamAccount.fileName] is the display name of the picked document.
 *
 * File playlists are NOT synced (per spec §3.2): the local copy can't travel, so a device that
 * pulls one shows a "re-import" affordance instead. Pure so the mapping is unit-testable.
 */
fun m3uAccountFromFile(playlistId: String, fileName: String, name: String? = null): XtreamAccount = XtreamAccount(
    id = playlistId,
    name = name?.trim()?.takeIf { it.isNotEmpty() } ?: fileName,
    baseUrl = "",
    username = "",
    password = "",
    sourceType = XtreamAccount.SOURCE_FILE,
    fileName = fileName
)

/**
 * Step 0 — the permanent id for a newly-picked file: the shared `m3u_file|<name>|<creation ms>` key
 * (the same shape a phone mints). Older TV builds minted `file:{uuid}`; those ids keep working (the
 * file store hashes any id) and are adopted onto the server's key on pull.
 */
fun newM3UFilePlaylistId(fileName: String, nowMs: Long = System.currentTimeMillis()): String =
    PlaylistKey.m3uFile(fileName, nowMs) ?: ("m3u_file|Playlist|$nowMs")

/** True for an account whose content comes from a parsed M3U URL rather than an Xtream API. */
fun XtreamAccount.isM3U(): Boolean = sourceType == XtreamAccount.SOURCE_URL

/** True for an account whose content comes from a locally-copied M3U file. */
fun XtreamAccount.isM3UFile(): Boolean = sourceType == XtreamAccount.SOURCE_FILE

/** True for either M3U-backed source (URL or File) — both browse/play through [M3UClient]. */
fun XtreamAccount.isM3UBacked(): Boolean = isM3U() || isM3UFile()

/**
 * True for a real Xtream panel — the ONLY source type with a player_api catalog, so the only
 * one the TMDB->stream match index (resolver/search/Radar) may be fed. M3U/Stalker accounts
 * play through their own lanes.
 */
fun XtreamAccount.isXtream(): Boolean = sourceType == XtreamAccount.SOURCE_XTREAM
