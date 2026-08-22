package com.nuvio.tv.player.mpv

import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import kotlin.math.roundToLong

/** libmpv mpv_event_id: MPV_EVENT_END_FILE (stable ABI value). */
private const val MPV_EVENT_END_FILE = 7

/**
 * The mpv property shadow, extracted from NuvioMpvSurfaceView into the fork-owned engine package
 * (research/tv-player-mpv-engine-ownership.md, A4a). mpv properties are observed on mpv's event
 * thread and mirrored into `@Volatile` fields so the main thread NEVER calls `mpv_get_property`
 * (which takes the core lock — a live demuxer holds it for seconds → ANR). See [[nuvio-mpv-anr-fix]].
 *
 * All fields are written only here (on the event thread) and read lock-free by the surface view.
 * [onEndFileError] is invoked on the event thread for an mpv END_FILE with reason "error"; the caller
 * must hop threads before touching UI/player state.
 */
internal class MpvPropertyShadow(
    private val onEndFileError: (fileError: String?) -> Unit,
) : MPV.EventObserver {

    @Volatile var obsPaused = true
    @Volatile var obsPausedForCache = false
    @Volatile var obsCoreIdle = false
    @Volatile var obsTimePosMs = 0L
    @Volatile var obsDurationMs = 0L
    @Volatile var obsVid: String? = null
    @Volatile var obsAid: String? = null
    @Volatile var obsSid: String? = null
    @Volatile var obsTrackList: MPVNode? = null
    @Volatile var obsVideoOutParams: MPVNode? = null
    @Volatile var obsVideoParams: MPVNode? = null
    @Volatile var obsVideoBitrate: Double? = null
    @Volatile var obsVideoFrameTicks = 0L
    @Volatile var obsAudioBitrate: Double? = null
    @Volatile var obsVoDroppedFrames = 0L
    @Volatile var obsVoDelayedFrames = 0L

    fun reset() {
        obsPaused = true
        obsPausedForCache = false
        obsCoreIdle = false
        obsTimePosMs = 0L
        obsDurationMs = 0L
        obsVid = null
        obsAid = null
        obsSid = null
        obsTrackList = null
        obsVideoOutParams = null
        obsVideoParams = null
        obsVideoBitrate = null
        obsAudioBitrate = null
        // Per-core counters: a fresh core reports 0, so re-init starts them there too.
        obsVoDroppedFrames = 0L
        obsVoDelayedFrames = 0L
    }

    override fun eventProperty(property: String) {
        // MPV_FORMAT_NONE: property became unavailable — fall back to the same
        // defaults a failed synchronous read used to produce.
        when (property) {
            "pause" -> obsPaused = true
            "paused-for-cache" -> obsPausedForCache = false
            "core-idle" -> obsCoreIdle = false
            "time-pos" -> obsTimePosMs = 0L
            "duration" -> obsDurationMs = 0L
            "vid" -> obsVid = null
            "aid" -> obsAid = null
            "sid" -> obsSid = null
            "track-list" -> obsTrackList = null
            "video-out-params" -> obsVideoOutParams = null
            "video-params" -> obsVideoParams = null
            "video-bitrate" -> obsVideoBitrate = null
            "audio-bitrate" -> obsAudioBitrate = null
            // Unavailable means no active VO — the same 0 a fresh core reports.
            "frame-drop-count" -> obsVoDroppedFrames = 0L
            "vo-delayed-frame-count" -> obsVoDelayedFrames = 0L
        }
    }

    override fun eventProperty(property: String, value: Long) {
        when (property) {
            "frame-drop-count" -> obsVoDroppedFrames = value
            "vo-delayed-frame-count" -> obsVoDelayedFrames = value
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> obsPaused = value
            "paused-for-cache" -> obsPausedForCache = value
            "core-idle" -> obsCoreIdle = value
        }
    }

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> obsTimePosMs = (value * 1000.0).roundToLong().coerceAtLeast(0L)
            "duration" -> obsDurationMs = (value * 1000.0).roundToLong().coerceAtLeast(0L)
            "video-bitrate" -> obsVideoBitrate = value.takeIf { it > 0.0 }
            "estimated-vf-fps" -> if (value > 0.0) obsVideoFrameTicks++
            "audio-bitrate" -> obsAudioBitrate = value.takeIf { it > 0.0 }
        }
    }

    override fun eventProperty(property: String, value: String) {
        when (property) {
            "vid" -> obsVid = value
            "aid" -> obsAid = value
            "sid" -> obsSid = value
        }
    }

    override fun eventProperty(property: String, value: MPVNode) {
        when (property) {
            "track-list" -> obsTrackList = value
            "video-out-params" -> obsVideoOutParams = value
            "video-params" -> obsVideoParams = value
        }
    }

    override fun event(eventId: Int, data: MPVNode) {
        if (eventId != MPV_EVENT_END_FILE) return
        // mpv_event_to_node shape: {"reason": "eof"|"stop"|"quit"|"error"|"redirect",
        //  "file_error": "<mpv error string>" (only when reason == "error")}.
        val map = runCatching { data.asMap() }.getOrNull() ?: return
        if (runCatching { map["reason"]?.asString() }.getOrNull() != "error") return
        val fileError = runCatching { map["file_error"]?.asString() }.getOrNull()
        onEndFileError(fileError)
    }
}
