package com.nuvio.tv.playback.wiring

import com.nuvio.tv.playback.core.ActiveWorkReleaseReason
import com.nuvio.tv.playback.core.AudioFocusPort
import com.nuvio.tv.playback.core.AudioOutputPreference
import com.nuvio.tv.playback.core.EngineType
import com.nuvio.tv.playback.core.EnginePreference
import com.nuvio.tv.playback.core.FrameRatePreference
import com.nuvio.tv.playback.core.NoopAudioFocusPort
import com.nuvio.tv.playback.core.PlaybackOutputApplication
import com.nuvio.tv.playback.core.PlaybackOutputController
import com.nuvio.tv.playback.core.PlaybackOutputRequest
import com.nuvio.tv.playback.core.PlaybackOutputStatus
import com.nuvio.tv.playback.core.PlaybackResult
import com.nuvio.tv.playback.core.SurfaceMode
import com.nuvio.tv.playback.core.TileHostConfig
import com.nuvio.tv.playback.core.VideoDimensions
import com.nuvio.tv.playback.settings.CleanPlaybackPreferences

/**
 * What a host composes differently in tile mode, as data. The host factory, the session factory and
 * the Media3 backend read this instead of each re-deciding what "a tile" means; the unit tests assert
 * on it without a device. [UNRESTRICTED] is the single full-screen / guide host exactly as it was
 * before tile mode existed.
 */
internal data class TileHostPlan(
    val requestsAudioFocus: Boolean,
    val createsMediaSession: Boolean,
    val switchesDisplayMode: Boolean,
    val forcesTunnellingOff: Boolean,
    val forcesPassthroughOff: Boolean,
    val forcesAudioOffloadOff: Boolean,
    /** Audio decoding is off from the first frame; the audible tile turns it on. */
    val startsAudioDisabled: Boolean,
    val enginesAllowed: Set<EngineType>,
    /** Null = every mode the production surface coordinator can build. */
    val surfaceModes: Set<SurfaceMode>?,
    /** Null = the device memory budget's own number. */
    val targetBufferBytes: Int?,
    val maxAdaptiveVideo: VideoDimensions?,
    val textTracksEnabled: Boolean,
) {
    companion object {
        val UNRESTRICTED = TileHostPlan(
            requestsAudioFocus = true,
            createsMediaSession = true,
            switchesDisplayMode = true,
            forcesTunnellingOff = false,
            forcesPassthroughOff = false,
            forcesAudioOffloadOff = false,
            startsAudioDisabled = false,
            enginesAllowed = EngineType.entries.toSet(),
            surfaceModes = null,
            targetBufferBytes = null,
            maxAdaptiveVideo = null,
            textTracksEnabled = true,
        )
    }
}

internal object TileHostComposition {
    fun plan(tile: TileHostConfig?): TileHostPlan = if (tile == null) {
        TileHostPlan.UNRESTRICTED
    } else {
        TileHostPlan(
            requestsAudioFocus = false,
            createsMediaSession = false,
            switchesDisplayMode = false,
            forcesTunnellingOff = true,
            forcesPassthroughOff = true,
            forcesAudioOffloadOff = true,
            startsAudioDisabled = true,
            enginesAllowed = setOf(EngineType.MEDIA3),
            surfaceModes = setOf(SurfaceMode.SURFACE_VIEW, SurfaceMode.TEXTURE_VIEW),
            targetBufferBytes = tile.bufferBytes,
            maxAdaptiveVideo = tile.maxVideo,
            textTracksEnabled = false,
        )
    }

    fun audioFocus(plan: TileHostPlan, real: () -> AudioFocusPort): AudioFocusPort =
        if (plan.requestsAudioFocus) real() else NoopAudioFocusPort

    /** The viewer's stored preferences, narrowed to what a tile can honour. Never persisted. */
    fun constrain(preferences: CleanPlaybackPreferences, plan: TileHostPlan): CleanPlaybackPreferences {
        if (plan == TileHostPlan.UNRESTRICTED) return preferences
        val playback = preferences.playback
        val cap = plan.maxAdaptiveVideo
        return preferences.copy(
            playback = playback.copy(
                engine = EnginePreference.MEDIA3,
                automaticFallback = false,
                audio = playback.audio.copy(
                    // PCM: the decoded stream is what mute and the single audible tile act on.
                    output = AudioOutputPreference.PCM,
                    downmixToStereo = false,
                    normalization = false,
                    skipSilence = false,
                    delayMs = 0,
                ),
                subtitles = playback.subtitles.copy(enabled = plan.textTracksEnabled, delayMs = 0),
                display = playback.display.copy(frameRate = FrameRatePreference.OFF, resolutionMatching = false),
                video = playback.video.copy(
                    maximumDimensions = narrower(playback.video.maximumDimensions, cap),
                ),
            ),
        )
    }

    private fun narrower(a: VideoDimensions?, b: VideoDimensions?): VideoDimensions? = when {
        a == null -> b
        b == null -> a
        else -> VideoDimensions(minOf(a.width, b.width), minOf(a.height, b.height))
    }
}

/** A tile never touches the HDMI mode: nothing to apply, nothing to restore. */
internal object NoopPlaybackOutputController : PlaybackOutputController {
    override suspend fun apply(
        request: PlaybackOutputRequest,
    ): PlaybackResult<PlaybackOutputApplication> =
        PlaybackResult.Success(PlaybackOutputApplication(PlaybackOutputStatus.DISABLED))

    override suspend fun reset(
        releasedGeneration: Long?,
        reason: ActiveWorkReleaseReason,
    ): PlaybackResult<Unit> = PlaybackResult.Success(Unit)
}
