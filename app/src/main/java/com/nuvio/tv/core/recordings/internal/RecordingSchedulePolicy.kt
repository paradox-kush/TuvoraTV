package com.nuvio.tv.core.recordings.internal

import com.nuvio.tv.core.recordings.api.CaptureMode
import com.nuvio.tv.core.recordings.api.RecordingCapabilities
import com.nuvio.tv.core.recordings.api.RecordingChannel
import com.nuvio.tv.core.recordings.api.RecordingProblem
import com.nuvio.tv.core.recordings.api.RecordingSchedule

sealed interface FireDecision {
    data class StartLive(val stopAtMs: Long) : FireDecision
    data class StartCatchUpSave(val fromMs: Long, val toMs: Long) : FireDecision
    data class Rearm(val atMs: Long) : FireDecision
    data object Missed : FireDecision
    data object Expired : FireDecision
}

sealed interface RetryDecision {
    data class RetryAt(val schedule: RecordingSchedule) : RetryDecision
    data class GiveUp(val problem: RecordingProblem) : RetryDecision
}

object RecordingSchedulePolicy {
    const val DEFAULT_PRE_MS = 2L * 60_000
    const val DEFAULT_POST_MS = 5L * 60_000
    const val CATCH_UP_SAVE_DELAY_MS = 10L * 60_000
    const val RETRY_MS = 30L * 60_000
    const val LIVE_RETRY_MS = 30_000L
    const val MAX_ATTEMPTS = 3
    const val OVERDUE_GRACE_MS = 60_000L
    const val EARLY_FIRE_TOLERANCE_MS = 60_000L

    fun modeFor(channel: RecordingChannel, programmeStartMs: Long, programmeEndMs: Long, nowMs: Long, platform: RecordingCapabilities): CaptureMode? = TODO()
    fun fireTimeMs(s: RecordingSchedule): Long = TODO()
    fun liveStopAtMs(s: RecordingSchedule): Long = TODO()
    fun catchUpWindowEndMs(s: RecordingSchedule): Long = TODO()
    fun onFire(s: RecordingSchedule, nowMs: Long): FireDecision = TODO()
    fun afterFailure(s: RecordingSchedule, problem: RecordingProblem, nowMs: Long, offline: Boolean): RetryDecision = TODO()
    fun clashes(candidate: RecordingSchedule, existing: List<RecordingSchedule>): List<RecordingSchedule> = TODO()
    fun isDuplicate(candidate: RecordingSchedule, existing: List<RecordingSchedule>): Boolean = TODO()
}
