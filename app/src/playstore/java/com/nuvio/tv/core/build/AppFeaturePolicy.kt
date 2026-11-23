package com.nuvio.tv.core.build

import com.nuvio.tv.BuildConfig

object AppFeaturePolicy {
    /** Analytics `build_flavor` super property; must match phone/Desktop values ("store" | "full"). */
    val buildFlavor: String = "store"
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
    // Store builds ship without built-in Usenet: the engine is a GPL-3 sidecar with a LAN setup
    // server, the binary is not packaged (build.gradle.kts "full" source set) and nothing exposes it.
    val usenetEnabled: Boolean = false
    // Audio-based subtitle sync (speech model, ~290 MB recognizer pool) stays hidden in store builds
    // until it is measured on a 2 GB box (Onn).
    val audioSubtitleSyncEnabled: Boolean = false
    val debugBackendSwitcherEnabled: Boolean = BuildConfig.IS_DEBUG_BUILD
}
