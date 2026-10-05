package com.nuvio.tv.core.sync

/**
 * B03/D5 — IPTV live-channel favourites always live in the local, Tuvora-synced library, even when the
 * visible Movies/Series library is sourced from Trakt/Simkl (the phone's rule; TV used to route them to
 * the tracking provider, so they never reached the phone and the phone's never reached the TV). Pure;
 * twin: NuvioMobile/NuvioDesktop `LibraryPullPolicy` + `LibraryRepository.localItems`.
 */
object LiveFavoriteStoragePolicy {

    /** A live channel favourite: stored locally (and synced) whatever the library source. */
    fun storesLocally(itemId: String, itemType: String): Boolean =
        itemType == LIVE_TYPE && itemId.startsWith("xtream:") && ":live:" in itemId

    /** The Tuvora library delta is pulled under a tracking provider too — favourites ride it. */
    fun pullsNuvioLibrary(trackingProviderActive: Boolean): Boolean = true

    const val LIVE_TYPE = "tv"
}
