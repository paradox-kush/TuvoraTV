package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.MpvHardwareDecodeMode
import com.nuvio.tv.data.local.SubtitleStyleSettings

/**
 * The contract the player controller talks to the mpv engine through.
 *
 * WHY THIS EXISTS (see research/tv-player-mpv-engine-ownership.md, Part A): the fork owns a large
 * off-main-thread mpv layer (a `ctl {}` command queue + an `obs*` property shadow) that lives inside
 * [NuvioMpvSurfaceView], a file we SHARE with upstream. Routing the controller through this interface
 * means every upstream mpv-facing call must compile against a named contract instead of reaching into
 * our engine internals, and it is the seam behind which the engine core is extracted.
 *
 * CONTRACT INVARIANTS (load-bearing today by convention only — do not break them in any implementation):
 *  - **Off-main-thread.** No method here may block the caller on an mpv core lock. Writes go through
 *    the implementation's command queue; reads come from its property shadow. See [[nuvio-mpv-anr-fix]].
 *  - **[applyHardwareDecodeMode] must precede the first [setMedia].** It seeds the field the engine
 *    reads in its init options; called later it only applies the live property.
 *  - **[ensureInitialized] / [releasePlayer] are a state machine, not two independent calls.**
 *    `ensureInitialized` blocks on a pending teardown and hard-fails if a previous native core is still
 *    alive; `releasePlayer` unregisters the surface callback. Re-attach after release depends on the
 *    surface still being available.
 *  - **[onPlaybackEndedWithError] fires on mpv's event thread.** The controller must hop threads before
 *    touching player/UI state.
 *  - **Identity matters.** Callers compare instances with `===`; never make this a value/wrapper type.
 *
 * Value types ([MpvVideoSnapshot], [MpvTrackSnapshot], [MpvTrack]) and the two settings enums are
 * referenced as-is for now; D4 (neutral engine types replacing the DataStore-owned
 * [SubtitleStyleSettings] / [MpvHardwareDecodeMode]) is a later step in the migration.
 */
interface MpvSurface {

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    fun ensureInitialized()
    fun setMedia(url: String, headers: Map<String, String>, startPositionMs: Long = 0L)
    fun setMediaUsingLoadfile(url: String, headers: Map<String, String>)
    fun releasePlayer()

    // ── Transport (routed through the command queue) ──────────────────────────
    fun setPaused(paused: Boolean)
    fun stopPlayback()
    fun seekToMs(positionMs: Long)
    fun setPlaybackSpeed(speed: Float)

    // ── State reads (lock-free, from the property shadow) ──────────────────────
    fun isPlayingNow(): Boolean
    fun isPausedForCacheNow(): Boolean
    fun isCoreIdleNow(): Boolean
    fun currentPositionMs(): Long
    fun durationMs(): Long
    fun hasVideoTrackNow(): Boolean
    fun hasVideoTrackSelectedNow(): Boolean
    fun videoFrameTicksNow(): Long
    fun voDroppedFrameCountNow(): Long
    fun voDelayedFrameCountNow(): Long
    fun readVideoSnapshot(): MpvVideoSnapshot
    fun readTrackSnapshot(): MpvTrackSnapshot

    // ── Tracks & subtitles ────────────────────────────────────────────────────
    fun selectAudioTrackById(trackId: Int): Boolean
    fun selectSubtitleTrackById(trackId: Int): Boolean
    fun disableSubtitles(): Boolean
    fun addAndSelectExternalSubtitle(url: String, title: String? = null, language: String? = null): Boolean
    fun applyAudioLanguagePreferences(languages: List<String>)
    fun applySubtitleLanguagePreferences(preferred: String, secondary: String?)
    fun applySubtitleStyle(style: SubtitleStyleSettings)
    fun setSubtitleDelayMs(delayMs: Int)
    fun reloadVideoTrack()

    // ── Audio/video config ────────────────────────────────────────────────────
    fun applyAudioAmplificationDb(db: Int)
    fun applyHardwareDecodeMode(mode: MpvHardwareDecodeMode)
    fun applyAspectMode(mode: AspectMode)

    // ── Events out (fired on mpv's event thread — see invariants) ──────────────
    var onPlaybackEndedWithError: ((fileError: String?) -> Unit)?
}
