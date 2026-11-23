package com.nuvio.tv.core.player

/**
 * Whether upstream's audio subtitle sync (sync 17) may run for the current stream.
 *
 * It has two parts that touch the stream: the audio tap (wraps the player's own extractors) and
 * the spot sampler, which opens EXTRA connections to the same URL to read dialogue ahead of
 * playback (one on LOW, several on HIGH; every one on mpv). On IPTV that is exactly what
 * [AutoSyncSourcePolicy] forbids: a Stalker `play_token` is burned by the first read and a
 * one-connection Xtream panel drops the player when a sampler takes the slot. So the audio sync
 * runs only where AutoSync itself may probe ([autoSyncMayProbe], computed with
 * [AutoSyncSourcePolicy.mayProbe]), and only in builds that ship it ([featureAvailable] =
 * `AppFeaturePolicy.audioSubtitleSyncEnabled`, off in the store flavor until measured on a 2 GB box).
 */
object AudioSyncSourcePolicy {
    fun mayRun(
        featureAvailable: Boolean,
        autoSyncEnabled: Boolean,
        audioSyncEnabled: Boolean,
        autoSyncMayProbe: Boolean,
    ): Boolean = featureAvailable && autoSyncEnabled && audioSyncEnabled && autoSyncMayProbe
}
