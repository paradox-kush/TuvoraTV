package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import com.nuvio.tv.core.analytics.AudioObservationSession
import com.nuvio.tv.core.analytics.AudioOutputTelemetry
import com.nuvio.tv.core.analytics.AudioSinkCapabilities
import com.nuvio.tv.data.local.InternalPlayerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Observes the audio pipeline for the current stream and emits ONE evidence-based
 * [AudioOutputTelemetry] `audio_output_profile`. It reports observed facts (codec, effective output
 * path + decoder, sink capabilities + provenance, settings) and an evidence-based status from an
 * [AudioObservationSession] — it never infers silence from codec-versus-sink.
 *
 * Runtime contract:
 *  - PLAYBACK-STATE-AWARE: only eligible (playing, non-buffering, non-suppressed) time counts toward
 *    the observation, so a slow-starting channel reads insufficient_observation, not a false silence.
 *  - GENERATION-GUARDED: each observation has a monotonic generation; a stale sample is rejected, and
 *    the loop abandons itself the moment the stream changes.
 *  - BOUNDED + CANCELLATION-AWARE: the sampling loop is capped by the session's max window and runs in
 *    the controller scope, so it is cancelled with playback.
 *  - THREADING: ExoPlayer is read on the application main thread (Main.immediate); PostHog is
 *    thread-safe and enforces consent/opt-out itself.
 *  - mpv: progress is UNKNOWN (no cheap rendered-buffer counter), never NOT_OBSERVED.
 */
internal fun PlayerRuntimeController.emitAudioOutputProfileIfNeeded() {
    val streamUrl = currentStreamUrl
    if (streamUrl.isBlank() || audioProfileEmittedStreamUrl == streamUrl) return
    val state0 = _uiState.value
    val hasSelectedAudio = state0.audioTracks.getOrNull(state0.selectedAudioTrackIndex) != null ||
        state0.audioTracks.any { it.isSelected }
    if (!hasSelectedAudio) return // nothing to observe yet — a later track refresh will retry
    audioProfileEmittedStreamUrl = streamUrl // claim: one observation per stream
    lastAudioPipelineErrorCode = null

    val generation = audioObservationGeneration.incrementAndGet()
    val engine = currentInternalPlayerEngine
    val mpv = engine != InternalPlayerEngine.EXOPLAYER
    val session = AudioObservationSession(generation = generation, progressUnobservable = mpv)

    scope.launch(Dispatchers.Main.immediate) {
        runCatching {
            val settings = playerSettingsDataStore.playerSettings.first()
            val startedAt = SystemClock.elapsedRealtime()
            var lastErr: String? = null

            while (isActive) {
                if (currentStreamUrl != streamUrl) return@runCatching // stream changed -> abandon
                val nowMs = SystemClock.elapsedRealtime() - startedAt
                val exo = _exoPlayer
                val playing = exo?.isPlaying == true
                val buffering = exo?.playbackState == Player.STATE_BUFFERING
                val suppressed = exo != null &&
                    exo.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE
                session.onPlaybackState(generation, nowMs, playing, buffering, suppressed)
                // The rendered-output-buffer counter is only meaningful for PCM DECODE (a MediaCodec
                // audio decoder is active). For passthrough/offload the shipped media3 fork does not run
                // one, so the counter is absent or does not reflect real output — we do NOT feed it, and
                // progress stays UNKNOWN rather than a false NOT_OBSERVED.
                if (!mpv && playbackAnalyticsDiagnostics.currentAudioDecoderName() != null) {
                    exo?.audioDecoderCounters?.apply { ensureUpdated() }?.renderedOutputBufferCount?.let {
                        session.onRenderedBuffers(generation, nowMs, it.toLong())
                    }
                }
                lastAudioPipelineErrorCode?.let {
                    if (it != lastErr) { lastErr = it; session.onAudioError(generation, nowMs, it) }
                }
                if (session.isDecidable(nowMs)) break
                delay(AUDIO_SAMPLE_INTERVAL_MS)
            }

            if (currentStreamUrl != streamUrl) return@runCatching
            val observation = session.evaluate(SystemClock.elapsedRealtime() - startedAt)

            val stateNow = _uiState.value
            val selected = stateNow.audioTracks.getOrNull(stateNow.selectedAudioTrackIndex)
                ?: stateNow.audioTracks.firstOrNull { it.isSelected }
            val decoderName = if (!mpv) playbackAnalyticsDiagnostics.currentAudioDecoderName() else null
            // No public runtime API exposes the AudioSink's configured output mode in the shipped fork,
            // so this is INFERRED from an active MediaCodec audio decoder (= PCM decode), never asserted
            // as observed runtime configuration. Absent a decoder (passthrough/offload) it stays unknown.
            val outputPath = when {
                !mpv && decoderName != null -> AudioOutputTelemetry.OUTPUT_PATH_PCM_DECODE
                else -> AudioOutputTelemetry.OUTPUT_PATH_UNKNOWN
            }
            val outputPathSource = when {
                !mpv && decoderName != null -> AudioOutputTelemetry.OUTPUT_PATH_SOURCE_INFERRED
                else -> AudioOutputTelemetry.OUTPUT_PATH_SOURCE_UNKNOWN
            }
            val recoveryStage = when {
                audioDisabledForcedStreamUrls.contains(streamUrl) -> AudioOutputTelemetry.RECOVERY_DISABLED
                safeAudioForcedStreamUrls.contains(streamUrl) -> AudioOutputTelemetry.RECOVERY_SAFE_AUDIO
                else -> AudioOutputTelemetry.RECOVERY_NONE
            }
            val sink = AudioOutputTelemetry.selectSinkCaps(
                defaultRouteProbe = readDefaultRouteSinkCaps(),
                enumerated = AudioSinkCapabilities.readEnumerated(context),
            )

            AudioOutputTelemetry.audioProfile(
                engine = if (mpv) "mpv" else "exo",
                rawAudioCodec = selected?.codec,
                audioChannels = selected?.channelCount,
                audioSampleRate = selected?.sampleRate,
                audioTrackCount = stateNow.audioTracks.size,
                selectedDecoder = decoderName,
                outputPath = outputPath,
                outputPathSource = outputPathSource,
                audioInputFormat = selected?.codec,
                recoveryStage = recoveryStage,
                sink = sink,
                forceOpticalPassthrough = settings.forceOpticalPassthrough,
                decoderPriority = settings.decoderPriority,
                observation = observation,
            )
        }
    }
}

/**
 * The DEFAULT-ROUTE sink capabilities, probed from media3's [AudioCapabilities] with
 * `routedDevice = null` — what the platform's default output route can pass through. This is NOT a
 * verified active route (actual routing is not observed); the provenance
 * ([AudioOutputTelemetry.SinkCapsSource.DEFAULT_ROUTE_PROBE]) says so. Probing named encodings never
 * claims incompatibility for an encoding we did not ask about, and carries no route label.
 */
@OptIn(UnstableApi::class)
private fun PlayerRuntimeController.readDefaultRouteSinkCaps(): AudioOutputTelemetry.SinkCaps? = runCatching {
    val detected = AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null)
    val probes = listOf(
        "pcm" to C.ENCODING_PCM_16BIT,
        "ac3" to C.ENCODING_AC3,
        "eac3" to C.ENCODING_E_AC3,
        "eac3" to C.ENCODING_E_AC3_JOC,
        "ac4" to C.ENCODING_AC4,
        "dts" to C.ENCODING_DTS,
        "dts_hd" to C.ENCODING_DTS_HD,
        "truehd" to C.ENCODING_DOLBY_TRUEHD,
    )
    val supported = probes.filter { detected.supportsEncoding(it.second) }.map { it.first }.toSet()
    AudioOutputTelemetry.SinkCaps(
        source = AudioOutputTelemetry.SinkCapsSource.DEFAULT_ROUTE_PROBE,
        supportedEncodings = supported,
        hasUnclassifiedEncodings = false,
        maxChannels = detected.maxChannelCount.takeIf { it > 0 },
        route = null, // no verified active route is claimed
    )
}.getOrNull()

/** Fast enough to catch a channel zap, sparse enough to be negligible over a multi-second window. */
private const val AUDIO_SAMPLE_INTERVAL_MS = 750L
