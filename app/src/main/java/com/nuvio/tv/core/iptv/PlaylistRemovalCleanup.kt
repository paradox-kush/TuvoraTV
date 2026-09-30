package com.nuvio.tv.core.iptv

/**
 * How a playlist left this device's list.
 *
 *  - [UserDelete] — the user removed it here. Everything keyed by it goes.
 *  - [SyncPull] — a pull from the server no longer lists it (deleted on another device). A pull can be
 *    transient (B24: a damaged/partial server copy has resurrected and wiped playlists before), so it
 *    drops only what rebuilds for free and never the user's own data.
 */
enum class PlaylistRemovalOrigin { UserDelete, SyncPull }

/**
 * One class of on-device state keyed by a playlist id. [userData] = something the user made that does
 * not rebuild on its own (dropped only on an explicit delete); everything else is a cache.
 *
 * Twin of NuvioMobile/NuvioDesktop's `features/iptv/PlaylistRemovalCleanup.kt` — same targets, same split;
 * each platform's executor ([IptvAccountPurge] here) maps a target onto its own stores.
 */
enum class PlaylistRemovalTarget(val userData: Boolean) {
    /** Ingested catalog + EPG store (programmes, meta, lazy-fetch stamps, in-flight shadow). */
    ContentDb(false),
    /** TMDB match index (items/cats/keys/idx_meta/tmdb_map/cursor/live_sid_history). */
    MatchIndex(false),
    /** Canonical-EPG mirror: the playlist's channel mapping and its schedule meta. */
    EpgMirror(false),
    /** Auto-refresh "last checked" stamp. */
    RefreshStamp(false),
    /**
     * The saved local copy of a file playlist (no-op for any other source). User data: its bytes came
     * from a document the user picked, which may no longer exist anywhere else, so it cannot rebuild.
     * A pull keeps it — if the playlist comes back, it re-ingests from this copy (decision 2026-09-30).
     */
    M3uFileCopy(true),
    /** Learned catch-up facts (winner dialect / panel clock + formats). */
    CatchUp(false),
    /** In-memory browse/search/registry caches. */
    SessionCaches(false),
    /** The hub's remembered provider, when it points at the removed playlist. */
    HubSelection(false),
    /** Hidden/pinned/renamed channels + categories for this playlist. */
    Overlay(true),
    /** Live favourites + recently-watched channels. */
    LiveChannels(true),
    /** Library, watch progress, watched items — synced; the removing device's own sync propagates. */
    SavedRefs(true),
}

/**
 * The pure decision "what to purge when a playlist goes away". The I/O ([IptvAccountPurge]) executes the plan;
 * this object never touches a store, so it tests without a DB, a Context, or the network.
 */
object PlaylistRemovalCleanup {

    /** Every target to clear for a playlist removed by [origin]. */
    fun plan(origin: PlaylistRemovalOrigin): Set<PlaylistRemovalTarget> =
        PlaylistRemovalTarget.entries
            .filter { origin == PlaylistRemovalOrigin.UserDelete || !it.userData }
            .toSet()

    /** Ids present [before] that [after] no longer lists, in [before]'s order. */
    fun removedIds(before: List<String>, after: List<String>): List<String> {
        val remaining = after.toSet()
        return before.filter { it !in remaining }.distinct()
    }

    /** Whether a remembered hub selection must be forgotten: it points at a removed playlist. */
    fun dropsHubSelection(rememberedAccountId: String?, removedId: String): Boolean =
        rememberedAccountId != null && rememberedAccountId == removedId

    /** A per-playlist timestamp map without the removed playlist's entry (same map when absent). */
    fun <V> withoutPlaylist(state: Map<String, V>, removedId: String): Map<String, V> =
        if (removedId in state) state - removedId else state
}
