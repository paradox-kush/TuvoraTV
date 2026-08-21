package com.nuvio.tv.core.iptv

/**
 * The browse + play surface every IPTV source exposes to the hub, live guide, search, and the
 * meta/stream short-circuits. [XtreamClient] talks to a live Xtream `player_api.php`; [M3UClient]
 * serves the same domain models out of a pre-ingested SQLite catalog. Call sites obtain the right
 * implementation for an account via [IptvClientFactory.clientFor] and never branch on source type
 * themselves — so the whole hybrid lane (registry ids, native detail, direct-stream playback)
 * stays source-agnostic.
 *
 * All methods return [Result] and never throw: a source failure degrades to "empty" the same way
 * Xtream's transient panel errors already do.
 */
interface IptvClient {
    suspend fun liveCategories(acc: XtreamAccount): Result<List<XtreamCategory>>
    suspend fun vodCategories(acc: XtreamAccount): Result<List<XtreamCategory>>
    suspend fun seriesCategories(acc: XtreamAccount): Result<List<XtreamCategory>>

    /** [categoryId] null = the whole catalog (used by the search index + "All channels"). */
    suspend fun liveChannels(acc: XtreamAccount, categoryId: String? = null): Result<List<XtreamChannel>>
    suspend fun vodMovies(acc: XtreamAccount, categoryId: String? = null): Result<List<XtreamMovie>>
    suspend fun series(acc: XtreamAccount, categoryId: String? = null): Result<List<XtreamSeriesItem>>

    /** Full episode list for a series (Xtream: get_series_info; M3U: grouped rows from the DB). */
    suspend fun seriesInfo(acc: XtreamAccount, seriesId: Int): Result<XtreamSeriesDetail>

    /** Now/next EPG for a channel. M3U has no per-channel EPG yet (P2c/XMLTV): returns empty. */
    suspend fun shortEpg(acc: XtreamAccount, streamId: Int, limit: Int = 4): Result<List<XtreamProgram>>

    /**
     * Warm this account's browse + guide data ahead of use, on the client's OWN scope — never the
     * caller's, because a whole-guide fetch must outlive the screen that triggered it (the ViewModel
     * -scope mistake that made the mirror re-sync forever). Fire-and-forget and safe to call on every
     * visit (each source single-flights + TTL-gates internally).
     *
     * Default no-op: Xtream/M3U warm their guide separately via [epg.XmltvClient.warm] (the store
     * lane). Stalker overrides this to pull its lineup + the ONE bulk `get_epg_info` into the local
     * store up front, so browsing shows now/next as the user scrolls instead of only after they
     * settle on a channel — the bulk otherwise runs inside a focus job that scrolling cancels
     * (root-caused 2026-08-20).
     */
    fun warm(acc: XtreamAccount) {}

    /** Account status (expiry/connections) for the settings row. M3U has none — default failure. */
    suspend fun accountInfo(acc: XtreamAccount): Result<XtreamAccountInfo> =
        Result.failure(UnsupportedOperationException("no account info for this source"))

    /**
     * Rebuilds a live/movie stream URL from a parsed content id on a registry cache miss
     * (deep link / saved library item). `kind` is "movie" or "live". Xtream derives it by
     * formula; M3U looks it up in the ingested catalog (URLs aren't formula-derivable there).
     * Returns null when the id isn't in this source (M3U miss) — the caller treats that as
     * "no longer available".
     *
     * [forceFresh] rides the one-shot 401/403/410 refresh ladder: produce the freshest possible
     * URL, bypassing any static/derived shortcut. Stalker mints a new create_link even when the
     * static-cmd policy would rule it unnecessary (the static URL just DIED — rebuilding it would
     * replay the failure); Xtream/M3U URLs are stable formulas, so they ignore it.
     */
    suspend fun resolveStreamUrl(acc: XtreamAccount, kind: String, streamId: Int, forceFresh: Boolean = false): String?

    /**
     * Why the LAST [resolveStreamUrl] answered null, when the source can say something better than
     * "it did not work" — currently only Stalker's session cap, which is a completely different
     * problem from a dead channel and is fixed by closing the other device, not by retrying.
     * Null = nothing more specific to say.
     */
    val lastResolveError: String? get() = null
}
