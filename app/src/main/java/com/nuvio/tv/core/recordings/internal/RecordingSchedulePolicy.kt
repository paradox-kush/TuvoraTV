package com.nuvio.tv.core.recordings.internal

import com.nuvio.tv.core.recordings.api.CaptureMode
import com.nuvio.tv.core.recordings.api.RecordingCapabilities
import com.nuvio.tv.core.recordings.api.RecordingChannel
import com.nuvio.tv.core.recordings.api.RecordingProblem
import com.nuvio.tv.core.recordings.api.RecordingSchedule

/** What an alarm that went off should do. */
sealed interface FireDecision {
    data class StartLive(val stopAtMs: Long) : FireDecision
    data class StartCatchUpSave(val fromMs: Long, val toMs: Long) : FireDecision

    /** The alarm came early (more than the tolerance): set it again for [atMs]. */
    data class Rearm(val atMs: Long) : FireDecision

    /** A live capture whose slot is over. */
    data object Missed : FireDecision

    /** A catch-up save whose programme has left the provider's window. */
    data object Expired : FireDecision
}

sealed interface RetryDecision {
    data class RetryAt(val schedule: RecordingSchedule) : RetryDecision
    data class GiveUp(val problem: RecordingProblem) : RetryDecision
}

/**
 * When a recording starts, whether an alarm may start it, and whether a failure is tried again.
 * Pure: the clock is the [nowMs] parameter, so every branch tests without a device.
 *
 * Adapted from homelessbrian/NuvioPlusIPTV (GPL-3.0), `LiveTvRecordingScheduler` (fireTime, fire,
 * clashWith and the catch-up retry rules), fixed to be pure with a capped, window-bounded retry.
 */
object RecordingSchedulePolicy {
    const val DEFAULT_PRE_MS = 2L * 60_000
    const val DEFAULT_POST_MS = 5L * 60_000
    const val CATCH_UP_SAVE_DELAY_MS = 10L * 60_000
    const val RETRY_MS = 30L * 60_000

    /** A live capture that failed inside its slot is tried again soon: waiting 30 min would miss it. */
    const val LIVE_RETRY_MS = 30_000L
    const val MAX_ATTEMPTS = 3
    const val OVERDUE_GRACE_MS = 60_000L
    const val EARLY_FIRE_TOLERANCE_MS = 60_000L
    private const val DAY_MS = 24L * 60 * 60_000
    private const val HOUR_MS = 60L * 60_000

    /**
     * CATCH_UP when the channel's archive holds the programme (the reliable path), else LIVE when
     * this platform can record live and the programme has not ended, else null.
     */
    fun modeFor(
        channel: RecordingChannel,
        programmeStartMs: Long,
        programmeEndMs: Long,
        nowMs: Long,
        platform: RecordingCapabilities,
    ): CaptureMode? {
        val days = channel.catchUp?.days ?: 0
        if (platform.canSaveCatchUp && days > 0 && nowMs <= windowEnd(programmeStartMs, days)) return CaptureMode.CATCH_UP
        if (platform.canRecordLive && programmeEndMs > nowMs) return CaptureMode.LIVE
        return null
    }

    fun fireTimeMs(s: RecordingSchedule): Long = when {
        s.nextTryMs > 0 -> s.nextTryMs
        s.mode == CaptureMode.CATCH_UP -> s.programmeEndMs + s.postMs + CATCH_UP_SAVE_DELAY_MS
        else -> s.programmeStartMs - s.preMs
    }

    fun liveStopAtMs(s: RecordingSchedule): Long = s.programmeEndMs + s.postMs

    /** The last moment the provider can be expected to still hold the programme (an hour of safety). */
    fun catchUpWindowEndMs(s: RecordingSchedule): Long = windowEnd(s.programmeStartMs, s.channel.catchUp?.days ?: 0)

    private fun windowEnd(startMs: Long, days: Int): Long = startMs + days * DAY_MS - HOUR_MS

    fun onFire(s: RecordingSchedule, nowMs: Long): FireDecision {
        val fireAt = fireTimeMs(s)
        return when (s.mode) {
            CaptureMode.LIVE -> {
                val stop = liveStopAtMs(s)
                when {
                    nowMs >= stop -> FireDecision.Missed
                    nowMs < fireAt - EARLY_FIRE_TOLERANCE_MS -> FireDecision.Rearm(fireAt)
                    else -> FireDecision.StartLive(stop)
                }
            }
            CaptureMode.CATCH_UP -> when {
                nowMs > catchUpWindowEndMs(s) -> FireDecision.Expired
                nowMs < fireAt - EARLY_FIRE_TOLERANCE_MS -> FireDecision.Rearm(fireAt)
                else -> FireDecision.StartCatchUpSave(s.programmeStartMs - s.preMs, s.programmeEndMs + s.postMs)
            }
        }
    }

    /**
     * Failures that a second try cannot fix. A full disk stays full, a channel with no archive or
     * an unsupported format stays that way, and a cancel or a missed slot is final.
     */
    private fun neverRetried(problem: RecordingProblem) = when (problem) {
        RecordingProblem.NO_SPACE, RecordingProblem.NO_CATCH_UP, RecordingProblem.UNSUPPORTED_FORMAT,
        RecordingProblem.CANCELLED_BY_USER, RecordingProblem.MISSED -> true
        else -> false
    }

    /**
     * After a failed capture: try again later, or stop for good. Offline failures do not use an
     * attempt (it was not the show's fault) but are still bounded by the window; every other
     * failure uses one and the total is capped at [MAX_ATTEMPTS].
     */
    fun afterFailure(s: RecordingSchedule, problem: RecordingProblem, nowMs: Long, offline: Boolean): RetryDecision {
        if (neverRetried(problem)) return RetryDecision.GiveUp(problem)
        val attempts = if (offline) s.attempts else s.attempts + 1
        if (!offline && attempts >= MAX_ATTEMPTS) return RetryDecision.GiveUp(problem)
        val retryAt = when (s.mode) {
            CaptureMode.LIVE -> {
                val at = nowMs + LIVE_RETRY_MS
                if (at >= liveStopAtMs(s)) return RetryDecision.GiveUp(problem)
                at
            }
            CaptureMode.CATCH_UP -> {
                val at = nowMs + RETRY_MS
                if (at > catchUpWindowEndMs(s)) return RetryDecision.GiveUp(problem)
                at
            }
        }
        return RetryDecision.RetryAt(s.copy(attempts = attempts, nextTryMs = retryAt))
    }

    /** Overlapping LIVE captures on the same provider source (one account often allows one stream). The UI warns, never blocks. */
    fun clashes(candidate: RecordingSchedule, existing: List<RecordingSchedule>): List<RecordingSchedule> {
        if (candidate.mode != CaptureMode.LIVE) return emptyList()
        val from = candidate.programmeStartMs - candidate.preMs
        val to = liveStopAtMs(candidate)
        return existing.filter {
            it.id != candidate.id && it.mode == CaptureMode.LIVE && it.channel.sourceId == candidate.channel.sourceId &&
                it.programmeStartMs - it.preMs < to && from < liveStopAtMs(it)
        }
    }

    fun isDuplicate(candidate: RecordingSchedule, existing: List<RecordingSchedule>): Boolean =
        existing.any { it.channel.key == candidate.channel.key && it.programmeStartMs == candidate.programmeStartMs }
}
