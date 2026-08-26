package com.nuvio.tv.core.analytics

/**
 * Live TV defaults to the ffmpeg/libmpv engine (product decision, 1.5.8).
 *
 * Why: the fleet's live freezes concentrate on ExoPlayer's hardware TS path — Fire TV/MediaTek
 * sticks froze on 20-40% of live starts across 1.5.5-1.5.7 (all freeze telemetry engine=exoplayer,
 * PostHog 30 d), while the same IPTV streams play clean on the mpv lane (iPhone and Android phones
 * already default live to libmpv; TV mpv live starts report no freezes). ExoPlayer's
 * MediaTek/Amlogic live-TS decoder bugs are documented upstream (androidx/media #2765,
 * ExoPlayer #678). The TV mpv lane is hardened: vo=gpu (fd-leak-safe), tiered demuxer budget,
 * ffmpeg auto-reconnect, bounded network reads, off-main mpv-ctl.
 *
 * This supersedes the parked per-SoC gate ([LiveHardwareDecoderPolicy]) — instead of gating the
 * worst decoders onto mpv, live opens on mpv everywhere the lane exists.
 *
 * Scope: LIVE feeds only (VOD and catch-up keep their existing defaults). The user's explicit
 * engine choice in Playback settings still wins, and devices without the mpv lane stay on
 * ExoPlayer. A wedged mpv live session still runs the same-engine reconnect ladder and, on
 * give-up, the actionable freeze overlay.
 *
 * Pure so the default is pinned by a unit test — flipping live back to ExoPlayer must consciously
 * change a named policy, not fall out of a refactor.
 */
internal object LiveDefaultEnginePolicy {

    /** True when a live feed with no explicit engine choice should open on libmpv. */
    fun preferMpvForLive(mpvAvailable: Boolean): Boolean = mpvAvailable
}
