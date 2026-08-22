package com.nuvio.tv.core.contracts

import okhttp3.Dns

/**
 * Neutral live/IPTV playback ports the player controller depends on, so the shared controller files
 * never name a `core.iptv` type directly and can be taken from upstream wholesale at merge time.
 * See research/tv-player-mpv-engine-ownership.md, Part B. Mirrors NuvioMobile's core/contracts ports
 * (IptvContentClassifier, LivePlaybackProvider, …) so fixes port between the apps.
 *
 * This package is NEUTRAL — it must not reference any fork feature package; the implementation
 * ([com.nuvio.tv.ui.screens.iptv.player.IptvLivePlayback]) lives on a fork path and is Hilt-bound.
 */

/** DoH-prepared live stream: the (possibly host-rewritten) URL + headers to hand the player. */
data class PreparedLive(val url: String, val headers: Map<String, String>)

/** A live channel to switch to (D-pad zap target). */
data class LiveChannelTarget(val id: String, val name: String, val streamUrl: String)

/** The next catch-up URL shape to try after a failed replay attempt. */
data class CatchUpDialectRetry(val channelName: String, val url: String)

/** Classifies content ids without exposing the Xtream id scheme to the controller. */
interface IptvContentClassifier {
    /** True when [id] is an IPTV live-channel id. */
    fun isLiveId(id: String): Boolean

    /** The first IPTV (Xtream-prefixed) id among [candidates], or null when none qualifies. */
    fun refreshableIptvId(candidates: List<String?>): String?
}

/** Per-playlist DNS/DoH preparation for live playback. */
interface PlaybackDnsPort {
    /** Blocking (call off the main thread): resolve the host through the playlist's DoH resolver. */
    fun prepareLive(contentId: String?, rawUrl: String): PreparedLive

    /** The okhttp DNS to use for [videoId]'s playlist, or null for system DNS. */
    fun dnsForVideoId(videoId: String?): Dns?
}

/** The catch-up (tv_archive) URL-dialect walk, owned outside the controller. */
interface CatchUpPlaybackPort {
    /** A frame reached the screen — remember the working URL shape for this account. */
    fun onPlayed(contentId: String?)

    /** Advance the dialect ladder on a failed replay; null when the walk is over / not catch-up. */
    fun onFailed(contentId: String?, errorCode: Int): CatchUpDialectRetry?
}

/** Resolves the next/previous live channel for in-place zapping. */
interface LiveChannelNavigator {
    fun relativeChannel(contentId: String, delta: Int): LiveChannelTarget?
}

/** One injected facade bundling the live/IPTV ports the player needs. */
interface LivePlayback {
    val classifier: IptvContentClassifier
    val dns: PlaybackDnsPort
    val catchUp: CatchUpPlaybackPort
    val channels: LiveChannelNavigator
}
