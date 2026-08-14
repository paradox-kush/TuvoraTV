package com.nuvio.tv.core.analytics

import android.util.Log
import com.posthog.PostHog

/**
 * Freeze-only telemetry for live channels.
 *
 * Live TV can wedge with no player error at all — see [LivePlaybackFreezePolicy] for the two
 * shapes — and a frozen picture is invisible to every reporting path the app already has:
 * the process stays alive so `app_exit` never fires, no exception is thrown so `$exception`
 * never fires, and the in-player "report issue" button is a manual action nobody takes.
 *
 * So this reports nothing during healthy playback. One event is emitted per freeze, and only
 * once the freeze *resolves* — either it cleared on its own, or the player was torn down while
 * still frozen (which is what changing channels to unstick it looks like from here). That makes
 * `recovered` the headline property: `false` means the viewer had to intervene.
 *
 * Volume is bounded twice over ([MAX_EVENTS_PER_SESSION], [MAX_EVENTS_PER_HOUR]) so a single
 * bad provider cannot flood the project.
 */
internal class LivePlaybackFreezeReporter(
    private val capture: (String, Map<String, Any>) -> Unit = { event, props ->
        PostHog.capture(event, properties = props)
    },
) {

    /** Playback-time context that is fixed for the channel; captured once when it starts. */
    data class Profile(
        val engine: String,
        val bufferEngineEnabled: Boolean,
        val minBufferMs: Int,
        val maxBufferMs: Int,
        val bufferForPlaybackMs: Int,
        val bufferForPlaybackAfterRebufferMs: Int,
        /** "ts" / "m3u8" / "mpd" / "other" — derived from the path only, never the host. */
        val streamContainer: String,
        /** "xtream" / "playlist" / "other" — which IPTV lane produced the channel. */
        val iptvKind: String,
    )

    private var profile: Profile? = null
    private var armed = false

    /** Whether a live channel is currently being watched for freezes. */
    val isArmed: Boolean get() = armed

    /**
     * Whether a freeze is open. A reconnect tears the player down and builds it back up, so the
     * wiring uses this to keep one freeze record spanning every attempt rather than closing it
     * as unrecovered the moment recovery starts.
     */
    val isFreezeOpen: Boolean get() = freezeActive

    /**
     * Records that a reconnect was fired for the open freeze, and rebases movement tracking on
     * the rebuilt player. A reconnected live stream restarts its timeline near zero, so without
     * rebasing the jump away from the old position would read as the picture coming back before
     * a single frame has actually been decoded.
     */
    fun onRecoveryAttempt(nowMs: Long) {
        if (!freezeActive) return
        freezeRecoveryAttempts += 1
        lastAdvancedPositionMs = 0L
        lastAdvanceAtMs = nowMs
        lastAdvancedBufferedPositionMs = 0L
        lastBufferedAdvanceAtMs = nowMs
        // The rebuilt player restarts its frame counter, so hold the current value as the baseline
        // rather than letting the reset itself read as the picture coming back.
        lastAdvancedVideoTicks = lastSeenVideoTicks
        lastVideoAdvanceAtMs = nowMs
    }

    private var playbackStartedAtMs = 0L
    private var lastAdvancedPositionMs = 0L
    private var lastAdvanceAtMs = 0L
    private var lastAdvancedBufferedPositionMs = 0L
    private var lastBufferedAdvanceAtMs = 0L
    private var lastAdvancedVideoTicks = 0L
    private var lastSeenVideoTicks = 0L
    private var lastVideoAdvanceAtMs = 0L

    private var freezeActive = false
    private var freezeKind: LivePlaybackFreezePolicy.Kind? = null
    private var freezeStartedAtMs = 0L
    private var freezeStartPositionMs = 0L
    private var freezeStartBufferedAheadMs = 0L
    private var freezeStartState = LivePlaybackFreezePolicy.PlaybackState.IDLE
    private var freezeMaxBufferedAheadMs = 0L
    private var freezePositionJumpedBack = false
    private var freezeStartRebufferCount = 0
    private var freezeRecoveryAttempts = 0

    private var eventsThisSession = 0
    private val recentEmitTimestampsMs = ArrayDeque<Long>()

    /**
     * Starts watching a live channel. Call only for live content, and only once the first frame
     * is on screen — startup buffering is a different problem and would otherwise report as a
     * freeze on every slow channel open.
     */
    fun onLivePlaybackStarted(
        profile: Profile,
        nowMs: Long,
        positionMs: Long,
        videoProgressTicks: Long = 0L,
    ) {
        this.profile = profile
        armed = true
        playbackStartedAtMs = nowMs
        lastAdvancedPositionMs = positionMs
        lastAdvanceAtMs = nowMs
        lastAdvancedBufferedPositionMs = positionMs
        lastBufferedAdvanceAtMs = nowMs
        lastAdvancedVideoTicks = videoProgressTicks
        lastSeenVideoTicks = videoProgressTicks
        lastVideoAdvanceAtMs = nowMs
        resetFreezeState()
        eventsThisSession = 0
    }

    /**
     * Feeds one sampled tick of player state and reports what it means, so the caller can act
     * on a freeze (reconnect) as well as record it. Cheap; safe to call from the progress loop.
     */
    fun onSample(
        nowMs: Long,
        positionMs: Long,
        bufferedPositionMs: Long,
        state: LivePlaybackFreezePolicy.PlaybackState,
        wantsToPlay: Boolean,
        rebufferCount: Int,
        rebufferTotalMs: Long,
        videoProgressTicks: Long = 0L,
        hasVideoTrack: Boolean = false,
    ): LivePlaybackFreezePolicy.Decision {
        if (!armed) return LivePlaybackFreezePolicy.Decision.Idle
        val bufferedAheadMs = (bufferedPositionMs - positionMs).coerceAtLeast(0L)
        lastSeenVideoTicks = videoProgressTicks

        val decision = LivePlaybackFreezePolicy.evaluate(
            LivePlaybackFreezePolicy.Input(
                state = state,
                wantsToPlay = wantsToPlay,
                positionMs = positionMs,
                lastAdvancedPositionMs = lastAdvancedPositionMs,
                sinceLastAdvanceMs = nowMs - lastAdvanceAtMs,
                bufferedPositionMs = bufferedPositionMs,
                lastAdvancedBufferedPositionMs = lastAdvancedBufferedPositionMs,
                sinceBufferedAdvanceMs = nowMs - lastBufferedAdvanceAtMs,
                freezeActive = freezeActive,
                activeKind = freezeKind,
                hasVideoTrack = hasVideoTrack,
                videoProgressTicks = videoProgressTicks,
                lastAdvancedVideoTicks = lastAdvancedVideoTicks,
                sinceVideoAdvanceMs = nowMs - lastVideoAdvanceAtMs,
                sincePlaybackStartMs = nowMs - playbackStartedAtMs,
            )
        )
        markBufferedAdvancedIfMoved(nowMs, bufferedPositionMs)
        markVideoAdvancedIfMoved(nowMs, videoProgressTicks)

        when (decision) {
            is LivePlaybackFreezePolicy.Decision.Idle -> {
                markAdvanced(nowMs, positionMs)
            }

            is LivePlaybackFreezePolicy.Decision.Start -> {
                freezeActive = true
                freezeKind = decision.kind
                freezeStartedAtMs = nowMs
                freezeStartPositionMs = positionMs
                freezeStartBufferedAheadMs = bufferedAheadMs
                freezeMaxBufferedAheadMs = bufferedAheadMs
                freezeStartState = state
                freezePositionJumpedBack = false
                freezeStartRebufferCount = rebufferCount
                Log.w(
                    TAG,
                    "LIVE_FREEZE: start kind=${decision.kind} state=$state " +
                        "pos=${positionMs}ms bufferedAhead=${bufferedAheadMs}ms",
                )
            }

            is LivePlaybackFreezePolicy.Decision.Continue -> {
                if (bufferedAheadMs > freezeMaxBufferedAheadMs) freezeMaxBufferedAheadMs = bufferedAheadMs
                if (positionMs < lastAdvancedPositionMs - LivePlaybackFreezePolicy.POSITION_TOLERANCE_MS) {
                    freezePositionJumpedBack = true
                }
            }

            is LivePlaybackFreezePolicy.Decision.Recover -> {
                if (positionMs < lastAdvancedPositionMs - LivePlaybackFreezePolicy.POSITION_TOLERANCE_MS) {
                    freezePositionJumpedBack = true
                }
                emit(
                    nowMs = nowMs,
                    recovered = true,
                    bufferedAheadAtResolveMs = bufferedAheadMs,
                    rebufferCount = rebufferCount,
                    rebufferTotalMs = rebufferTotalMs,
                )
                resetFreezeState()
                markAdvanced(nowMs, positionMs)
            }
        }
        return decision
    }

    /**
     * Ends the watch. An open freeze is flushed as unrecovered — the viewer changed channel,
     * backed out, or the player was rebuilt to escape it.
     */
    fun onLivePlaybackStopped(
        nowMs: Long,
        positionMs: Long,
        bufferedPositionMs: Long,
        rebufferCount: Int,
        rebufferTotalMs: Long,
    ) {
        if (armed && freezeActive) {
            emit(
                nowMs = nowMs,
                recovered = false,
                bufferedAheadAtResolveMs = (bufferedPositionMs - positionMs).coerceAtLeast(0L),
                rebufferCount = rebufferCount,
                rebufferTotalMs = rebufferTotalMs,
            )
        }
        armed = false
        profile = null
        resetFreezeState()
    }

    private fun markAdvanced(nowMs: Long, positionMs: Long) {
        lastAdvancedPositionMs = positionMs
        lastAdvanceAtMs = nowMs
    }

    /**
     * Tracked independently of the playhead: during a rebuffer the buffered edge keeps moving
     * while the playhead does not, and that difference is what separates "filling up" from
     * "the pipe is dead".
     */
    private fun markBufferedAdvancedIfMoved(nowMs: Long, bufferedPositionMs: Long) {
        val delta = bufferedPositionMs - lastAdvancedBufferedPositionMs
        if (delta > LivePlaybackFreezePolicy.POSITION_TOLERANCE_MS ||
            delta < -LivePlaybackFreezePolicy.POSITION_TOLERANCE_MS
        ) {
            lastAdvancedBufferedPositionMs = bufferedPositionMs
            lastBufferedAdvanceAtMs = nowMs
        }
    }

    /**
     * Tracked independently of both the playhead and the buffered edge, because it is the only
     * one of the three that audio cannot keep alive. Any change is a rendered frame — the value
     * is a counter, so unlike a position it needs no tolerance band.
     */
    private fun markVideoAdvancedIfMoved(nowMs: Long, videoProgressTicks: Long) {
        if (videoProgressTicks != lastAdvancedVideoTicks) {
            lastAdvancedVideoTicks = videoProgressTicks
            lastVideoAdvanceAtMs = nowMs
        }
    }

    private fun resetFreezeState() {
        freezeActive = false
        freezeKind = null
        freezeStartedAtMs = 0L
        freezeStartPositionMs = 0L
        freezeStartBufferedAheadMs = 0L
        freezeMaxBufferedAheadMs = 0L
        freezeStartState = LivePlaybackFreezePolicy.PlaybackState.IDLE
        freezePositionJumpedBack = false
        freezeStartRebufferCount = 0
        freezeRecoveryAttempts = 0
    }

    private fun emit(
        nowMs: Long,
        recovered: Boolean,
        bufferedAheadAtResolveMs: Long,
        rebufferCount: Int,
        rebufferTotalMs: Long,
    ) {
        val activeProfile = profile ?: return
        val kind = freezeKind ?: return
        if (!allowEmit(nowMs)) {
            Log.w(TAG, "LIVE_FREEZE: suppressed (rate cap) kind=$kind recovered=$recovered")
            return
        }

        val frozenMs = (nowMs - freezeStartedAtMs).coerceAtLeast(0L)
        val properties = buildMap<String, Any> {
            put("engine", activeProfile.engine)
            put("freeze_kind", kind.name.lowercase())
            put("state_at_freeze", freezeStartState.name.lowercase())
            put("recovered", recovered)
            put("frozen_ms", frozenMs)
            put("played_ms_before_freeze", (freezeStartedAtMs - playbackStartedAtMs).coerceAtLeast(0L))
            put("freeze_index", eventsThisSession + 1)

            // The buffer hypothesis: on a realtime source the player can need more buffered
            // media to resume than the source can ever produce. If the freeze is a stall and
            // buffered-ahead sits below buffer_for_playback_after_rebuffer_ms the whole time,
            // that is the cause rather than a dropped connection.
            put("buffered_ahead_at_freeze_ms", freezeStartBufferedAheadMs)
            put("buffered_ahead_at_resolve_ms", bufferedAheadAtResolveMs)
            put("buffered_ahead_max_ms", freezeMaxBufferedAheadMs)
            put("buffer_engine_enabled", activeProfile.bufferEngineEnabled)
            put("min_buffer_ms", activeProfile.minBufferMs)
            put("max_buffer_ms", activeProfile.maxBufferMs)
            put("buffer_for_playback_ms", activeProfile.bufferForPlaybackMs)
            put("buffer_for_playback_after_rebuffer_ms", activeProfile.bufferForPlaybackAfterRebufferMs)

            // A backwards jump is Media3 reconnecting an unknown-length source from the start.
            put("position_jumped_back", freezePositionJumpedBack)
            // How many reconnects the fix fired before the picture came back (or gave up).
            put("recovery_attempts", freezeRecoveryAttempts)
            put("rebuffers_during_freeze", (rebufferCount - freezeStartRebufferCount).coerceAtLeast(0))
            put("rebuffer_count", rebufferCount)
            put("rebuffer_total_ms", rebufferTotalMs)

            put("stream_container", activeProfile.streamContainer)
            put("iptv_kind", activeProfile.iptvKind)
        }

        capture(EVENT, properties)
        eventsThisSession += 1
        recentEmitTimestampsMs.addLast(nowMs)
        Log.w(TAG, "LIVE_FREEZE: reported kind=$kind recovered=$recovered frozenMs=$frozenMs")
    }

    private fun allowEmit(nowMs: Long): Boolean {
        if (eventsThisSession >= MAX_EVENTS_PER_SESSION) return false
        while (recentEmitTimestampsMs.isNotEmpty() && nowMs - recentEmitTimestampsMs.first() > RATE_WINDOW_MS) {
            recentEmitTimestampsMs.removeFirst()
        }
        return recentEmitTimestampsMs.size < MAX_EVENTS_PER_HOUR
    }

    companion object {
        private const val TAG = "LiveFreeze"
        const val EVENT = "live_playback_freeze"

        /** Per player session. A channel that freezes repeatedly is proven by the first few. */
        const val MAX_EVENTS_PER_SESSION = 3

        /** Process-wide backstop across channel changes. */
        const val MAX_EVENTS_PER_HOUR = 6
        private const val RATE_WINDOW_MS = 60L * 60L * 1000L

        /** Container from the path only — the provider host is never sent. */
        fun streamContainerOf(url: String?): String {
            if (url.isNullOrBlank()) return "unknown"
            val path = url.substringBefore('?').substringBefore('#').lowercase()
            return when {
                path.endsWith(".m3u8") -> "m3u8"
                path.endsWith(".mpd") -> "mpd"
                path.endsWith(".ts") -> "ts"
                path.endsWith(".mkv") -> "mkv"
                path.endsWith(".mp4") -> "mp4"
                else -> "other"
            }
        }
    }
}
