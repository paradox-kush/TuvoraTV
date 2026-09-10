package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.analytics.AudioOutputTelemetry
import com.nuvio.tv.core.analytics.AudioSinkCapabilities
import com.nuvio.tv.data.local.InternalPlayerEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Emits one [AudioOutputTelemetry] `audio_output_profile` per stream so "some channels have no audio"
 * becomes measurable: it pairs the stream's audio codec/channels with the device sink's real
 * capabilities and the user's audio settings, and records whether audio actually rendered.
 *
 * Timing: the sample is taken a few seconds AFTER a selected audio track appears, so
 * `audioDecoderCounters.renderedOutputBufferCount` has had time to move — reading it at track-change
 * time would false-flag healthy audio as silent. Read-only and fully guarded: a diagnostic must never
 * perturb playback. Called from both engines' track-refresh paths; the per-stream guard de-dupes.
 */
internal fun PlayerRuntimeController.emitAudioOutputProfileIfNeeded() {
    val streamUrl = currentStreamUrl
    if (streamUrl.isBlank() || audioProfileEmittedStreamUrl == streamUrl) return
    val state = _uiState.value
    val selected = state.audioTracks.getOrNull(state.selectedAudioTrackIndex)
        ?: state.audioTracks.firstOrNull { it.isSelected }
        ?: return // no audio track established yet — wait for the next refresh
    audioProfileEmittedStreamUrl = streamUrl // claim before the async body so a burst emits once

    val engine = currentInternalPlayerEngine
    val trackCount = state.audioTracks.size
    val rawCodec = selected.codec
    val channels = selected.channelCount
    val sampleRate = selected.sampleRate

    scope.launch {
        runCatching {
            delay(AUDIO_PROFILE_SAMPLE_DELAY_MS)
            if (currentStreamUrl != streamUrl) return@runCatching // stream changed under us
            val settings = playerSettingsDataStore.playerSettings.first()
            val sink = AudioSinkCapabilities.read(context)
            // Direct "is audio being heard" signal for ExoPlayer; mpv exposes no cheap equivalent.
            val audioRendered: Boolean? = if (engine == InternalPlayerEngine.EXOPLAYER) {
                _exoPlayer?.audioDecoderCounters?.renderedOutputBufferCount?.let { it > 0 }
            } else {
                null
            }
            AudioOutputTelemetry.audioProfile(
                engine = if (engine == InternalPlayerEngine.EXOPLAYER) "exo" else "mpv",
                rawAudioCodec = rawCodec,
                audioChannels = channels,
                audioSampleRate = sampleRate,
                audioTrackCount = trackCount,
                sink = sink,
                forceOpticalPassthrough = settings.forceOpticalPassthrough,
                decoderPriority = settings.decoderPriority,
                audioRendered = audioRendered,
                // The safe-audio -> PCM -> disabled ladder stage is not yet threaded here; the
                // codec x sink x settings x audio_rendered correlation already localizes the fault.
                recoveryStage = null,
                audioErrorCode = null,
            )
        }
    }
}

/** Long enough for audio decoder output buffers to accrue, short enough to catch a channel zap. */
private const val AUDIO_PROFILE_SAMPLE_DELAY_MS = 4_000L
