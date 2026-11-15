package com.nuvio.tv.playback.multiview

import com.nuvio.tv.core.multiview.MultiviewPanePlayback
import com.nuvio.tv.core.multiview.TileFailure
import com.nuvio.tv.core.multiview.TileFailurePolicy
import com.nuvio.tv.core.multiview.TilePhase
import com.nuvio.tv.core.multiview.TileSignal
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.GraphRejectionReason
import com.nuvio.tv.playback.core.PlaybackFailure
import com.nuvio.tv.playback.core.PlaybackSnapshot
import com.nuvio.tv.playback.core.PlaybackState

/**
 * The host's snapshot, read as a multiview tile sees it. Pure: no engine, no Android. Failures are
 * classified from the real signal the pipeline already carries (HTTP status, decoder, network, no
 * progress), never from a message: [TileFailurePolicy] decides what each one means.
 */
internal object TilePlaybackMapper {
    private val carriesPicture = setOf(PlaybackState.PLAYING, PlaybackState.DEGRADED)

    fun phaseOf(snapshot: PlaybackSnapshot): TilePhase = when (snapshot.state) {
        PlaybackState.FAILED -> TilePhase.FAILED
        PlaybackState.PLAYING, PlaybackState.DEGRADED ->
            if (snapshot.isPlaying || snapshot.progress.renderedVideoFrame) TilePhase.PLAYING else TilePhase.WAITING
        PlaybackState.IDLE, PlaybackState.STOPPED -> TilePhase.IDLE
        else -> TilePhase.WAITING
    }

    /**
     * How far this open has got. Higher is progress: a later pipeline stage, or one more piece of
     * evidence (bytes arrived, tracks found, decoder ready, renderer ready, a frame drawn). A reconnect
     * cycling back through the early stages ranks below what the first attempt reached.
     */
    fun stageOf(snapshot: PlaybackSnapshot): Int {
        val rank = when (snapshot.state) {
            PlaybackState.RESOLVING -> 1
            PlaybackState.SELECTING_GRAPH -> 2
            PlaybackState.ATTACHING_SURFACE -> 3
            PlaybackState.STARTING_PRIMARY -> 4
            PlaybackState.PLAYING, PlaybackState.DEGRADED -> 5
            else -> 0
        }
        val p = snapshot.progress
        val evidence = listOf(
            p.receivedBytes, p.discoveredTracks, p.decoderReady, p.rendererReady, p.renderedVideoFrame,
        ).count { it }
        return rank * 10 + evidence
    }

    /**
     * A tile has no second engine, so a failure the session would hand off ends in "no eligible graph
     * once the failed engine is excluded": the real cause is the failure seen just before. [priorCause] is
     * that earlier failure; it replaces the dead end only, never a failure that already says something.
     */
    fun effectiveFailure(final: PlaybackFailure?, priorCause: PlaybackFailure?): PlaybackFailure? {
        val deadEnd = final != null && final.code == FailureCode.NO_ELIGIBLE_GRAPH &&
            final.graphRejection?.removedBy?.containsKey(GraphRejectionReason.HANDOFF_EXCLUDED) == true
        return if (deadEnd && priorCause != null) priorCause else final
    }

    fun failureOf(failure: PlaybackFailure?, stallMs: Long, priorCause: PlaybackFailure? = null): TileFailure {
        val cause = effectiveFailure(failure, priorCause)
        return TileFailurePolicy.classify(if (cause == null) TileSignal.Unknown else signalOf(cause, stallMs))
    }

    fun signalOf(failure: PlaybackFailure, stallMs: Long): TileSignal {
        failure.httpStatus?.let { return TileSignal.Http(it) }
        return when (failure.code) {
            FailureCode.AUTHORIZATION_REJECTED -> TileSignal.Http(403)
            FailureCode.PROVIDER_CONNECTION_LIMIT -> TileSignal.Http(429)
            FailureCode.SOURCE_NOT_FOUND -> TileSignal.Http(404)
            FailureCode.NETWORK_UNREACHABLE,
            FailureCode.NETWORK_TIMEOUT,
            FailureCode.TLS_HANDSHAKE_FAILED,
            -> TileSignal.Network
            FailureCode.NO_PROGRESS,
            FailureCode.LIVE_RECONNECT_EXHAUSTED,
            -> TileSignal.Stall(stallMs)
            // Media3 folds "no decoder / init failed / exceeds capabilities" into one code; none of
            // them is a format this device is known never to play, so it counts as a decoder problem.
            FailureCode.VIDEO_DECODER_UNAVAILABLE -> TileSignal.Codec(TileFailurePolicy.MEDIA3_DECODER_INIT_FAILED)
            FailureCode.VIDEO_DECODER_FAILED,
            FailureCode.VIDEO_RENDERER_FAILED,
            -> TileSignal.Codec(TileFailurePolicy.MEDIA3_DECODING_FAILED)
            FailureCode.RESOURCE_BUDGET_EXCEEDED -> TileSignal.Codec(TileFailurePolicy.CODEC_INSUFFICIENT_RESOURCE)
            FailureCode.NO_ELIGIBLE_GRAPH,
            FailureCode.DRM_UNSUPPORTED,
            -> TileSignal.UnsupportedFormat
            else -> TileSignal.Unknown
        }
    }

    fun map(
        snapshot: PlaybackSnapshot,
        stalled: Boolean,
        stallMs: Long,
        priorCause: PlaybackFailure? = null,
    ): MultiviewPanePlayback {
        if (snapshot.state == PlaybackState.FAILED) {
            return MultiviewPanePlayback(failure = failureOf(snapshot.failure, stallMs, priorCause))
        }
        if (stalled) return MultiviewPanePlayback(failure = TileFailure.NoData)
        val playing = snapshot.state in carriesPicture && snapshot.isPlaying
        val reconnecting = snapshot.isReconnecting || snapshot.state == PlaybackState.LIVE_RECONNECTING
        val waiting = phaseOf(snapshot) != TilePhase.PLAYING
        return MultiviewPanePlayback(
            isBuffering = !playing && (snapshot.isBuffering || waiting),
            isPlaying = playing,
            isReconnecting = reconnecting,
        )
    }
}
