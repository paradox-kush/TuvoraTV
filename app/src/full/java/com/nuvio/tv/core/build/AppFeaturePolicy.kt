package com.nuvio.tv.core.build

import com.nuvio.tv.BuildConfig

object AppFeaturePolicy {
    /** Analytics `build_flavor` super property; must match phone/Desktop values ("store" | "full"). */
    val buildFlavor: String = "full"
    val pluginsEnabled: Boolean = true
    val addonsEnabled: Boolean = true
    val inAppUpdatesEnabled: Boolean = true
    val inAppTrailerPlaybackEnabled: Boolean = true
    val externalTrailerPlaybackEnabled: Boolean = true
    val supportNuvioEnabled: Boolean = true
    val trailerPlaybackMode: TrailerPlaybackMode = TrailerPlaybackMode.IN_APP
    val imdbRatingLogoEnabled: Boolean = true
    val p2pEnabled: Boolean = true
    val debridEnabled: Boolean = true
    val addonStreamSourcesEnabled: Boolean = true
    // Built-in Usenet (GPL-3 sidecar engine + LAN setup server): GitHub builds only.
    val usenetEnabled: Boolean = true
    // Audio-based subtitle sync (on-device speech model): GitHub builds only until measured on a 2 GB box.
    val audioSubtitleSyncEnabled: Boolean = true
    val debugBackendSwitcherEnabled: Boolean = BuildConfig.IS_DEBUG_BUILD
}
