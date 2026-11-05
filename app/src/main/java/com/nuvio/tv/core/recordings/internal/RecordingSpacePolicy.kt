package com.nuvio.tv.core.recordings.internal

import com.nuvio.tv.core.recordings.api.LowSpacePolicy
import com.nuvio.tv.core.recordings.api.Recording
import com.nuvio.tv.core.recordings.api.RecordingProblem
import com.nuvio.tv.core.recordings.api.RecordingStatus

sealed interface SpaceDecision {
    data object Proceed : SpaceDecision
    data class DeleteThenProceed(val ids: List<String>) : SpaceDecision
    data class Refuse(val problem: RecordingProblem) : SpaceDecision
}

sealed interface CaptureSpaceDecision {
    data object Continue : CaptureSpaceDecision
    data class Stop(val problem: RecordingProblem) : CaptureSpaceDecision
    data class DeleteThenContinue(val ids: List<String>) : CaptureSpaceDecision
}

object RecordingSpacePolicy {
    const val MIN_FREE_BYTES = 500L * 1024 * 1024
    const val DEFAULT_BITRATE_BPS = 8_000_000L
    fun estimateBytes(durationMs: Long, bitrateBps: Long? = null): Long = TODO()
    fun check(freeBytes: Long, needBytes: Long, policy: LowSpacePolicy, deletable: List<Recording>): SpaceDecision = TODO()
    fun duringCapture(freeBytes: Long, expectedBytesUntilNextCheck: Long, policy: LowSpacePolicy, deletable: List<Recording>): CaptureSpaceDecision = TODO()
}
