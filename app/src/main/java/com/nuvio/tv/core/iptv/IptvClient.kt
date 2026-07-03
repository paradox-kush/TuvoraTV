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
     * Rebuilds a live/movie stream URL from a parsed content id on a registry cache miss
     * (deep link / saved library item). `kind` is "movie" or "live". Xtream derives it by
     * formula; M3U looks it up in the ingested catalog (URLs aren't formula-derivable there).
     * Returns null when the id isn't in this source (M3U miss) — the caller treats that as
     * "no longer available".
     */
    suspend fun resolveStreamUrl(acc: XtreamAccount, kind: String, streamId: Int): String?
}
