package com.nuvio.tv.playback.core

/**
 * What a live runtime-window freeze is, and what answers it.
 *
 * Field case (Google TV Streamer, MediaTek decoder, 2026-11): the picture freezes while the audio
 * plays on. Every reconnect rebuilt the player, decoder and surface, showed one frame, and froze
 * again; the first frame ended the incident, so each freeze started over at "plain reconnect" and
 * the libmpv handoff was never reached. The window's own metrics already say which case it is:
 *
 * - [FreezeKind.SOURCE]: video and audio both flat. The stream stalled. A fresh link may help, so
 *   it reconnects, bounded by the 6 per 120 s reconnect budget.
 * - [FreezeKind.VIDEO_ONLY]: video flat, audio advancing. The source is fine; the decoder or the
 *   way Media3 feeds it is stuck. One reconnect is cheap and cures Amlogic stalls; a second freeze
 *   in the same incident, or any freeze inside probation, goes to libmpv.
 *
 * Pure, so it tests without the player or the network.
 */
object LiveFreezePolicy {
    enum class FreezeKind { VIDEO_ONLY, SOURCE }

    /** The rung of the ladder that answers a freeze. */
    enum class Action {
        /** Rebuild the pipeline with a fresh request. */
        RECONNECT,

        /** Hand the channel to libmpv. */
        HANDOFF,

        /** The picture is stuck but audio plays and no other engine remains: say so, do not fail. */
        KEEP_PLAYING,
    }

    /** "Video is moving" means at least this many rendered frames per second of window. */
    const val MIN_VIDEO_FRAMES_PER_SECOND = 1L

    /** Null when the engine cannot report the rendered-frame fact for this window. */
    fun videoAdvanced(
        before: PlaybackEngineMetricsSnapshot,
        after: PlaybackEngineMetricsSnapshot,
        windowMs: Long,
    ): Boolean? {
        if (before.generation != after.generation) return null
        val beforeFrames = before.videoFramesRendered ?: return null
        val afterFrames = after.videoFramesRendered ?: return null
        val required = (windowMs / 1_000L * MIN_VIDEO_FRAMES_PER_SECOND).coerceAtLeast(1L)
        return afterFrames - beforeFrames >= required
    }

    /** Null when audio progress is not reported; the caller treats that as "not advancing". */
    fun audioAdvanced(
        before: PlaybackEngineMetricsSnapshot,
        after: PlaybackEngineMetricsSnapshot,
    ): Boolean? {
        val beforeAudio = before.audioBuffersRendered ?: return null
        val afterAudio = after.audioBuffersRendered ?: return null
        return afterAudio > beforeAudio
    }

    /** Null when video moved, or when the video fact is unavailable (no freeze can be claimed). */
    fun classify(
        before: PlaybackEngineMetricsSnapshot,
        after: PlaybackEngineMetricsSnapshot,
        windowMs: Long,
    ): FreezeKind? {
        if (videoAdvanced(before, after, windowMs) != false) return null
        return if (audioAdvanced(before, after) == true) FreezeKind.VIDEO_ONLY else FreezeKind.SOURCE
    }

    /**
     * @param priorVideoOnlyFreezes video-only freezes already seen in this incident
     * @param inProbation a reconnect showed its first frame and the incident is not yet over
     * @param handoffAvailable libmpv is eligible for this stream and we are not on it already
     */
    fun decide(
        kind: FreezeKind,
        engine: EngineType,
        handoffAvailable: Boolean,
        priorVideoOnlyFreezes: Int,
        inProbation: Boolean,
    ): Action = when (kind) {
        FreezeKind.SOURCE -> Action.RECONNECT
        FreezeKind.VIDEO_ONLY -> when {
            // There is nowhere left to hand off, and reconnecting would only cut the audio that works.
            engine == EngineType.LIBMPV -> Action.KEEP_PLAYING
            handoffAvailable && (priorVideoOnlyFreezes >= 1 || inProbation) -> Action.HANDOFF
            else -> Action.RECONNECT
        }
    }

    /** Consecutive passing runtime windows that end probation: the stable time, in whole windows. */
    fun probationWindows(stableMs: Long, windowMs: Long): Int {
        require(windowMs > 0) { "Window must be positive" }
        return ((stableMs + windowMs - 1) / windowMs).toInt().coerceAtLeast(1)
    }
}
