package com.nuvio.tv.core.iptv

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Turns whatever the user pastes into an [XtreamAccount]. IPTV users always have a
 * portal/M3U URL, so we accept any of:
 *   http://host:port/get.php?username=U&password=P&type=m3u_plus&output=ts
 *   http://host:port/player_api.php?username=U&password=P
 *   http://host:port/?username=U&password=P
 * The path is ignored; we only need scheme+host+port and the username/password query params.
 *
 * ponytail: single paste field beats a 3-field TV form. Manual host/user/pass entry is
 * the upgrade path if someone has creds without a URL.
 */
fun parseXtreamAccount(input: String, name: String? = null): XtreamAccount? {
    val url = input.trim().toHttpUrlOrNull() ?: return null
    val user = url.queryParameter("username")?.takeIf { it.isNotBlank() } ?: return null
    val pass = url.queryParameter("password")?.takeIf { it.isNotBlank() } ?: return null
    val defaultPort = if (url.scheme == "https") 443 else 80
    val base = buildString {
        append(url.scheme).append("://").append(url.host)
        if (url.port != defaultPort) append(":").append(url.port)
    }
    return XtreamAccount(
        id = "$base|$user",
        name = name?.trim()?.takeIf { it.isNotEmpty() } ?: url.host,
        baseUrl = base,
        username = user,
        password = pass
    )
}

/**
 * Applies the shared "Add Playlist" options (EPG override, DNS provider, auto-refresh) collected by
 * the form onto a parsed/built [XtreamAccount]. Blank EPG normalizes to null. Pure so the
 * field->account mapping is unit-testable independent of the (Hilt-bound) settings ViewModel.
 */
fun XtreamAccount.withPlaylistOptions(
    epgUrl: String?,
    dnsProvider: String,
    autoRefreshHours: Int
): XtreamAccount = copy(
    epgUrl = epgUrl?.trim()?.takeIf { it.isNotEmpty() },
    dnsProvider = dnsProvider,
    autoRefreshHours = autoRefreshHours
)

/**
 * Builds an account from manually-entered fields (server/portal URL + username + password).
 * The server field may be "host", "host:port", or a full "http(s)://host:port[/path]" — we keep
 * only scheme+host+port. Defaults to http when no scheme is given (Xtream panels are usually http).
 */
fun xtreamAccountFromFields(serverUrl: String, username: String, password: String, name: String? = null): XtreamAccount? {
    val user = username.trim()
    val pass = password.trim()
    if (user.isEmpty() || pass.isEmpty()) return null
    val raw = serverUrl.trim()
    if (raw.isEmpty()) return null
    val withScheme = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
    val url = withScheme.toHttpUrlOrNull() ?: return null
    val defaultPort = if (url.scheme == "https") 443 else 80
    val base = buildString {
        append(url.scheme).append("://").append(url.host)
        if (url.port != defaultPort) append(":").append(url.port)
    }
    return XtreamAccount(
        id = "$base|$user",
        name = name?.trim()?.takeIf { it.isNotEmpty() } ?: url.host,
        baseUrl = base,
        username = user,
        password = pass
    )
}
