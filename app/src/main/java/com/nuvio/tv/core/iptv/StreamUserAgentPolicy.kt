package com.nuvio.tv.core.iptv

/**
 * Resolves the per-playlist stream User-Agent an IPTV account should send when fetching media,
 * or null to fall back to the engine default ([com.nuvio.tv.playback.core.DEFAULT_STREAM_USER_AGENT]).
 *
 * Why this exists (a real support loop): a provider behind Cloudflare answered the *stream* request
 * with a block — observed live as `HTTP 456` with an empty body — while the same account listed
 * channels and played fine in another IPTV app on the same network. The panel's API calls go out
 * under an honest `NuvioTV/x` UA and pass the WAF; only the stream fetch, pinned to a spoofed-Chrome
 * default UA, tripped the "claims to be a browser but isn't" bot rule. Letting the viewer pin an
 * honest IPTV-client UA (what VLC / IBO / a MAG box send) per playlist is the fix, and it was
 * already honored for M3U playlists — this closes the same gap for Xtream and Stalker.
 *
 * Pure: no Android, no network — the decision tests in isolation.
 *
 * The storage quirk it hides: an M3U-URL/-file playlist stashes its optional UA in
 * [XtreamAccount.username] (that field is unused as a credential for a URL playlist); Xtream and
 * Stalker carry it in the dedicated [XtreamAccount.userAgent]. Resolving both here keeps the one
 * caller ([com.nuvio.tv.core.iptv.playback.IptvProviderPlaybackResolver]) from having to know which.
 */
object StreamUserAgentPolicy {

    /** The override to send, or null to let the engine apply its default UA. Never blank. */
    fun resolve(account: XtreamAccount): String? {
        val raw = if (account.stashesUserAgentInUsername()) account.username else account.userAgent
        return raw?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** M3U URL/file playlists reuse the (credential-less) username slot to hold the UA. */
    private fun XtreamAccount.stashesUserAgentInUsername(): Boolean =
        sourceType == XtreamAccount.SOURCE_URL || sourceType == XtreamAccount.SOURCE_FILE
}
