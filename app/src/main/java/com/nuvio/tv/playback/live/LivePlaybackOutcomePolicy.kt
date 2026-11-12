package com.nuvio.tv.playback.live

import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.PlaybackState

/**
 * B256: the terminal outcome of one live viewing, decided without the player (bounded-load rule:
 * every wait has a deadline and ends Loaded or Failed, and the outcome is reported).
 *
 * A channel that played and then lost its provider went LIVE_RECONNECTING -> RELEASING -> FAILED and
 * nothing reported it; the screen it happened on (the channel list over fullscreen video) kept the
 * failure behind its panel. This policy names, per session generation:
 *  - when a wait (tuning, reconnecting, recovering) started, and the deadline the screen stops waiting at
 *    even if the session never answers ([WAIT_BACKSTOP_MS], a backstop: the session's own watchdogs,
 *    20 s resolve limit, 3-attempt reconnect cap and 90 s tune deadline all end a wait well before it);
 *  - the ONE report a failed or timed-out wait produces (a later frame of the same failure is not a
 *    second outcome; a new generation or a recovered picture opens a new incident).
 *
 * Reports carry closed-vocabulary values only: the outcome word, the duration and the [FailureCode]
 * name. Never a host, URL, channel name or engine message.
 */
object LivePlaybackOutcomePolicy {

    /**
     * Backstop for a wait the session never ends: one tune deadline per reconnect attempt the session
     * may spend (3 per incident) plus the first. Longer than every session bound on purpose, so the
     * session's precise failure always arrives first and this only fires if the session itself wedges.
     */
    val WAIT_BACKSTOP_MS: Long = LiveTuneDeadlinePolicy.DEADLINE_MS * 4

    enum class Phase { IDLE, WAITING, PLAYING, FAILED }

    fun phaseOf(state: PlaybackState): Phase = when (state) {
        PlaybackState.RESOLVING,
        PlaybackState.SELECTING_GRAPH,
        PlaybackState.ATTACHING_SURFACE,
        PlaybackState.STARTING_PRIMARY,
        PlaybackState.RECOVERING_IN_PLACE,
        PlaybackState.HANDING_OFF_ONCE,
        PlaybackState.LIVE_RECONNECTING,
        // A release barrier on the way to FAILED (or to a fresh request) is still a wait.
        PlaybackState.RELEASING,
        -> Phase.WAITING
        PlaybackState.PLAYING,
        PlaybackState.DEGRADED,
        -> Phase.PLAYING
        PlaybackState.FAILED -> Phase.FAILED
        PlaybackState.IDLE,
        PlaybackState.STOPPED,
        -> Phase.IDLE
    }

    enum class Outcome(val label: String) { FAILED("failed"), TIMED_OUT("timeout") }

    data class Report(val outcome: Outcome, val durationMs: Long, val failureCode: FailureCode?)

    /**
     * Single-owner state (the guide's owner dispatcher). Feed every published snapshot to [observe] and
     * call [expire] when [deadlineAtMs] passes.
     */
    class Tracker {
        private var generation: Long? = null
        private var waitStartedAtMs: Long? = null
        private var incidentStartedAtMs: Long? = null
        private var reported = false

        /** The wait passed its backstop: the screen shows it as failed (with Retry) until a new picture or tune. */
        var stalled: Boolean = false
            private set

        /** When the open wait must end, or null when nothing is being waited for. */
        val deadlineAtMs: Long?
            get() = waitStartedAtMs?.takeIf { !reported }?.plus(WAIT_BACKSTOP_MS)

        fun observe(generation: Long, phase: Phase, failureCode: FailureCode?, nowMs: Long): Report? {
            if (this.generation != generation) {
                this.generation = generation
                waitStartedAtMs = null
                incidentStartedAtMs = null
                reported = false
                stalled = false
            }
            return when (phase) {
                Phase.WAITING -> {
                    if (waitStartedAtMs == null) waitStartedAtMs = nowMs
                    if (incidentStartedAtMs == null) incidentStartedAtMs = nowMs
                    null
                }
                Phase.PLAYING -> {
                    // A picture ends the incident: a later failure is a new outcome.
                    waitStartedAtMs = null
                    incidentStartedAtMs = null
                    reported = false
                    stalled = false
                    null
                }
                Phase.FAILED -> {
                    waitStartedAtMs = null
                    stalled = false
                    if (reported) {
                        null
                    } else {
                        reported = true
                        val started = incidentStartedAtMs ?: nowMs
                        Report(Outcome.FAILED, (nowMs - started).coerceAtLeast(0L), failureCode)
                    }
                }
                Phase.IDLE -> {
                    waitStartedAtMs = null
                    incidentStartedAtMs = null
                    null
                }
            }
        }

        /** The backstop: a wait past its deadline ends here as TIMED_OUT, reported once. */
        fun expire(nowMs: Long): Report? {
            val deadline = deadlineAtMs ?: return null
            if (nowMs < deadline) return null
            reported = true
            stalled = true
            val started = incidentStartedAtMs ?: waitStartedAtMs ?: nowMs
            return Report(Outcome.TIMED_OUT, (nowMs - started).coerceAtLeast(0L), null)
        }
    }
}
