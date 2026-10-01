package com.nuvio.tv.core.build

import com.nuvio.tv.BuildConfig

object AppFeaturePolicy {
    val pluginsEnabled: Boolean = false
    // Store builds hide the Stremio-style addon system: no user-installable stream
    // sources, so the listing is a pure BYO-IPTV player (Play 4.2.2 / Apple 5.2.3).
    val addonsEnabled: Boolean = false
    val inAppUpdatesEnabled: Boolean = false
    val inAppTrailerPlaybackEnabled: Boolean = false
    val externalTrailerPlaybackEnabled: Boolean = true
    val supportNuvioEnabled: Boolean = false
    val trailerPlaybackMode: TrailerPlaybackMode = TrailerPlaybackMode.EXTERNAL
    val imdbRatingLogoEnabled: Boolean = false
    // Store builds ship without torrent streaming, same posture as addons above.
    val p2pEnabled: Boolean = false
    // Store builds hide Debrid (torrent-cache services) like P2P above. Saved keys still
    // sync untouched; only the capability is compiled off (DebridSettings.featureAvailable).
    val debridEnabled: Boolean = false
    // Store builds keep add-ons for discovery (catalogs, metadata, subtitles) but never as playback
    // sources: add-ons sync in from tuvora.co / full builds (AddonSourcePolicy).
    val addonStreamSourcesEnabled: Boolean = false
    val debugBackendSwitcherEnabled: Boolean = BuildConfig.IS_DEBUG_BUILD
}
