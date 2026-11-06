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

    /**
     * Present only for a replay that asked for a continuation window (an M3U replay): the player then
     * follows the playhead into the shows after the one launched. Null = a plain replay that ends with its programme.
     */
    fun continuation(contentId: String?): CatchUpContinuation? = null

    /** The programme covering [atMs] (else the next one) on this replay's channel; null when the guide has none. */
    suspend fun programmeAt(contentId: String?, atMs: Long): CatchUpProgramme? = null

    /** A new replay window starting at [atMs] for a replay whose window ran out; null when none can be built. */
    fun restartAt(contentId: String?, atMs: Long): CatchUpRestart? = null

    /**
     * Ties this replay's continuation state to the caller: dispose the handle when the player goes away
     * and the state is dropped with it (the port keeps a small bounded table only as a backstop).
     */
    fun hold(contentId: String?): com.nuvio.tv.core.lifecycle.Disposable = com.nuvio.tv.core.lifecycle.Disposable { }

    /** The channel's live URL, to rejoin live when the replay reaches the live edge. */
    fun liveUrl(contentId: String?): String? = null

    /**
     * Non-null only for a replay whose link is spent by playing (a Stalker archive): the words to show
     * when it fails ([startupHang]: no frame in time, else the engine failed). Such a replay is never
     * re-opened, probed or walked to another URL shape; the viewer's Retry goes through [reopen].
     */
    fun singleUseFailure(contentId: String?, startupHang: Boolean): String? = null

    /** A FRESH link for a single-use replay (Retry); null when [contentId] is not one. */
    suspend fun reopen(contentId: String?): CatchUpReopen? = null
}

/** The answer to a single-use replay's Retry: a new link to open, or the portal's refusal in words. */
sealed interface CatchUpReopen {
    data class Link(val url: String) : CatchUpReopen {
        override fun toString(): String = "CatchUpReopen.Link(hasUrl=true)"
    }

    data class Refused(val message: String) : CatchUpReopen
}

/** A programme as the player titles it: [title] is ready to show. */
data class CatchUpProgramme(val title: String, val startMs: Long, val endMs: Long)

/** What a continuing replay starts from: the window it requested and the programme it opened on. */
data class CatchUpContinuation(val windowEndMs: Long, val programme: CatchUpProgramme)

/** A fresh replay window: the link, where it starts and where it ends (wall clock). */
data class CatchUpRestart(val url: String, val startMs: Long, val windowEndMs: Long)

/** One injected facade bundling the IPTV ports still used by finite catch-up playback. */
interface LivePlayback {
    val classifier: IptvContentClassifier
    val dns: PlaybackDnsPort
    val catchUp: CatchUpPlaybackPort
}
