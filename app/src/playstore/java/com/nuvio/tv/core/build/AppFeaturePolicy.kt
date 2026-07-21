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
    val trailerPlaybackMode: TrailerPlaybackMode = TrailerPlaybackMode.EXTERNAL
    val imdbRatingLogoEnabled: Boolean = false
    // Store builds ship without torrent streaming, same posture as addons above.
    val p2pEnabled: Boolean = false
    val debugBackendSwitcherEnabled: Boolean = BuildConfig.IS_DEBUG_BUILD
}
