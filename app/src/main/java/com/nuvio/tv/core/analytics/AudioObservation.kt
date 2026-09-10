package com.nuvio.tv.core.analytics

/**
 * Evidence-based audio status. Driven ONLY by what was observed — pipeline progress and pipeline
 * errors — never by codec-versus-sink capability: a channel whose E-AC-3 the sink cannot pass through
 * but the app decodes to PCM is working, and must not be called a failure. "unknown" is kept strictly
 * separate from "not observed": the engine failing to expose a counter (mpv) is not evidence of silence.
 */
enum class AudioStatus {
    /** Rendered output buffers advanced during eligible playing time — the pipeline made progress. */
    PROGRESS_OBSERVED,

    /** Enough eligible playing time elapsed and rendered output buffers did NOT advance. */
    PROGRESS_NOT_OBSERVED,

    /** Not enough eligible (playing, non-buffering, non-suppressed) time elapsed to decide. */
    INSUFFICIENT_OBSERVATION,

    /** An audio-pipeline error (AudioTrack init/write, audio decoder init/decode) was observed. */
    OUTPUT_ERROR,

    /** The engine cannot report rendered-buffer progress (mpv) — no evidence either way. */
    UNKNOWN,
}

/**
 * Tri-state pipeline progress derived from the engine's rendered-output-buffer counter. This is
 * OBSERVED PIPELINE PROGRESS, not confirmed audibility: a rendered buffer can still be muted, routed
 * elsewhere, or (for passthrough/offload) handed to the sink without this counter moving. Kept
 * deliberately coarse and honestly named.
 */
enum class AudioPipelineProgress { OBSERVED, NOT_OBSERVED, UNKNOWN }

/** The observation outcome — all facts, no hypotheses. */
data class AudioObservationResult(
    val status: AudioStatus,
    val progress: AudioPipelineProgress,
    val eligibleObservationMs: Long,
    val bufferingMs: Long,
    /** Rendered-output-buffer delta across eligible time; null when the engine exposes no counter. */
    val renderedBufferDelta: Long?,
    val errorCode: String?,
    /** Short closed-vocabulary token explaining [status]. */
    val reason: String,
    val rearmCount: Int,
)

/**
 * Pure, generation-guarded observation of whether the audio pipeline made progress. It accumulates
 * ONLY eligible time (intending to play, not buffering / paused / suppressed), so a channel that
 * spends its first seconds buffering is reported [AudioStatus.INSUFFICIENT_OBSERVATION], never a false
 * "not observed". It rejects callbacks tagged with a stale playback generation, and can be re-armed a
 * bounded number of times after a disruptive change (route / track / engine / recovery-stage) so a
 * successful recovery is what gets reported. No Android, player, clock, or PostHog here — the adapter
 * feeds it real callbacks and their timestamps.
 */
class AudioObservationSession(
    val generation: Long,
    private val minEligibleMs: Long = DEFAULT_MIN_ELIGIBLE_MS,
    private val maxWindowMs: Long = DEFAULT_MAX_WINDOW_MS,
    private val maxRearms: Int = DEFAULT_MAX_REARMS,
    /** true for engines with no rendered-buffer counter (mpv): progress is UNKNOWN, never NOT_OBSERVED. */
    private val progressUnobservable: Boolean = false,
) {
    private var startedAtMs: Long? = null
    private var lastTickMs: Long? = null
    private var eligibleMs = 0L
    private var bufferingMs = 0L
    private var intendsToPlay = false
    private var eligible = false
    private var baselineRendered: Long? = null
    private var latestRendered: Long? = null
    private var errorCode: String? = null
    private var rearms = 0

    private fun stale(generation: Long) = generation != this.generation

    /** Advances the accumulators to [atMs] under the CURRENT eligibility before any state change. */
    private fun accrue(atMs: Long) {
        if (startedAtMs == null) startedAtMs = atMs
        val last = lastTickMs
        if (last != null && atMs > last) {
            val dt = atMs - last
            when {
                eligible -> eligibleMs += dt
                intendsToPlay -> bufferingMs += dt // wants to play but is buffering/suppressed
            }
        }
        lastTickMs = atMs
    }

    fun onPlaybackState(
        generation: Long,
        atMs: Long,
        playing: Boolean,
        buffering: Boolean,
        suppressed: Boolean,
    ) {
        if (stale(generation)) return
        accrue(atMs)
        intendsToPlay = playing || buffering
        eligible = playing && !buffering && !suppressed
    }

    fun onRenderedBuffers(generation: Long, atMs: Long, count: Long) {
        if (stale(generation)) return
        accrue(atMs)
        if (baselineRendered == null) baselineRendered = count
        latestRendered = count
    }

    fun onAudioError(generation: Long, atMs: Long, code: String) {
        if (stale(generation)) return
        accrue(atMs)
        if (errorCode == null) errorCode = code
    }

    /**
     * A disruptive change (route / track / engine / recovery-stage). Re-arms a bounded follow-up:
     * the eligible window resets and progress re-baselines from here, so a successful recovery is
     * measured, not the pre-change silence. Beyond [maxRearms] the change is ignored so a flapping
     * stream cannot observe forever. An error already seen is retained.
     */
    fun onDisruptiveChange(generation: Long, atMs: Long) {
        if (stale(generation) || rearms >= maxRearms) return
        accrue(atMs)
        rearms += 1
        eligibleMs = 0L
        bufferingMs = 0L
        baselineRendered = latestRendered
    }

    /** True once an error is seen OR enough eligible time elapsed OR the wall-clock window expired. */
    fun isDecidable(atMs: Long): Boolean {
        accrue(atMs)
        if (errorCode != null || eligibleMs >= minEligibleMs) return true
        val started = startedAtMs ?: return false
        return atMs - started >= maxWindowMs
    }

    fun evaluate(atMs: Long): AudioObservationResult {
        accrue(atMs)
        val delta = baselineRendered?.let { b -> latestRendered?.let { l -> (l - b).coerceAtLeast(0L) } }
        val progress = when {
            progressUnobservable || delta == null -> AudioPipelineProgress.UNKNOWN
            delta > 0L -> AudioPipelineProgress.OBSERVED
            else -> AudioPipelineProgress.NOT_OBSERVED
        }
        val status = when {
            errorCode != null -> AudioStatus.OUTPUT_ERROR
            progress == AudioPipelineProgress.UNKNOWN -> AudioStatus.UNKNOWN
            eligibleMs < minEligibleMs -> AudioStatus.INSUFFICIENT_OBSERVATION
            progress == AudioPipelineProgress.OBSERVED -> AudioStatus.PROGRESS_OBSERVED
            else -> AudioStatus.PROGRESS_NOT_OBSERVED
        }
        val reason = when (status) {
            AudioStatus.OUTPUT_ERROR -> "audio_error"
            AudioStatus.UNKNOWN -> "engine_reports_no_counter"
            AudioStatus.INSUFFICIENT_OBSERVATION -> "eligible_time_below_min"
            AudioStatus.PROGRESS_OBSERVED -> "rendered_buffers_advanced"
            AudioStatus.PROGRESS_NOT_OBSERVED -> "no_rendered_buffer_progress"
        }
        return AudioObservationResult(
            status = status,
            progress = progress,
            eligibleObservationMs = eligibleMs,
            bufferingMs = bufferingMs,
            renderedBufferDelta = delta,
            errorCode = errorCode,
            reason = reason,
            rearmCount = rearms,
        )
    }

    companion object {
        const val DEFAULT_MIN_ELIGIBLE_MS = 3_000L
        const val DEFAULT_MAX_WINDOW_MS = 15_000L
        const val DEFAULT_MAX_REARMS = 3
    }
}
