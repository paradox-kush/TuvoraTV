package com.nuvio.tv.core.recordings.internal

import com.nuvio.tv.core.recordings.api.LowSpacePolicy
import com.nuvio.tv.core.recordings.api.Recording
import com.nuvio.tv.core.recordings.api.RecordingProblem
import com.nuvio.tv.core.recordings.api.RecordingStatus

sealed interface SpaceDecision {
    data object Proceed : SpaceDecision
    data class DeleteThenProceed(val ids: List<String>) : SpaceDecision
    data class Refuse(val problem: RecordingProblem, val needBytes: Long? = null, val freeBytes: Long? = null) : SpaceDecision
}

sealed interface CaptureSpaceDecision {
    data object Continue : CaptureSpaceDecision
    data class Stop(val problem: RecordingProblem) : CaptureSpaceDecision
    data class DeleteThenContinue(val ids: List<String>) : CaptureSpaceDecision
}

/**
 * Whether there is room to record, and what may be deleted to make room. Pure. Asked on every
 * path: before a start, and again while capturing. [MIN_FREE_BYTES] is never consumed, and a
 * capture in progress is never deleted.
 */
object RecordingSpacePolicy {
    const val MIN_FREE_BYTES = 500L * 1024 * 1024

    /** A typical HD IPTV stream, used when the stream's own bitrate is unknown. */
    const val DEFAULT_BITRATE_BPS = 8_000_000L

    fun estimateBytes(durationMs: Long, bitrateBps: Long? = null): Long {
        val bps = bitrateBps?.takeIf { it > 0 } ?: DEFAULT_BITRATE_BPS
        return durationMs.coerceAtLeast(0) * bps / 8_000L
    }

    /** Room beyond the floor that a start deleting-to-make-room aims for, so the capture does not stop at once. */
    const val HEAD_START_BYTES = 64L * 1024 * 1024

    /**
     * Before a start. An ESTIMATE is never a reason to refuse or to delete: only the floor is.
     * Above the floor it proceeds. At or below it, STOP refuses; DELETE_OLDEST deletes the oldest
     * finished recordings (never a capture, never the newest of a series) until it is above the floor
     * plus [HEAD_START_BYTES], and refuses (deleting nothing) if even that cannot clear the floor.
     */
    fun preStart(freeBytes: Long, policy: LowSpacePolicy, deletable: List<Recording>): SpaceDecision {
        val usable = freeBytes - MIN_FREE_BYTES
        if (usable > 0) return SpaceDecision.Proceed
        if (policy == LowSpacePolicy.STOP) return SpaceDecision.Refuse(RecordingProblem.NO_SPACE)
        val ids = ArrayList<String>()
        var freed = 0L
        for (r in deletionCandidates(deletable)) {
            if (usable + freed >= HEAD_START_BYTES) break
            ids += r.id
            freed += r.sizeBytes
        }
        return if (usable + freed > 0) SpaceDecision.DeleteThenProceed(ids) else SpaceDecision.Refuse(RecordingProblem.NO_SPACE)
    }

    /**
     * A size the provider DECLARED (Content-Length). Only the part still to be written counts, so a
     * resume compares the remainder. Refuse carries the numbers for the failure row.
     */
    fun knownSize(freeBytes: Long, totalBytes: Long, onDiskBytes: Long, policy: LowSpacePolicy, deletable: List<Recording>): SpaceDecision =
        check(freeBytes, (totalBytes - onDiskBytes).coerceAtLeast(0), policy, deletable)

    /** Whether the estimate plus the floor exceeds the free space: only drives the "may stop early" notice. */
    fun spaceTight(freeBytes: Long, durationMs: Long, bitrateBps: Long? = null): Boolean =
        estimateBytes(durationMs, bitrateBps) + MIN_FREE_BYTES > freeBytes

    private fun deletionCandidates(deletable: List<Recording>): List<Recording> {
        // The newest recording of each series is the one "keep last" counts on: it stays.
        val protectedIds = deletable.filter { it.seriesRuleId != null }
            .groupBy { it.seriesRuleId }
            .values.mapNotNull { group -> group.maxWithOrNull(compareBy<Recording> { it.programmeStartMs }.thenBy { it.id })?.id }
            .toSet()
        return deletable
            .filter { it.status == RecordingStatus.DONE && it.sizeBytes > 0 && it.id !in protectedIds }
            .sortedWith(compareBy<Recording> { it.programmeStartMs }.thenBy { it.id })
    }

    fun check(freeBytes: Long, needBytes: Long, policy: LowSpacePolicy, deletable: List<Recording>): SpaceDecision {
        val usable = freeBytes - MIN_FREE_BYTES
        if (needBytes <= usable) return SpaceDecision.Proceed
        val refuse = SpaceDecision.Refuse(RecordingProblem.NO_SPACE, needBytes, freeBytes)
        if (policy == LowSpacePolicy.STOP) return refuse
        val ids = ArrayList<String>()
        var freed = 0L
        for (r in deletionCandidates(deletable)) {
            if (usable + freed >= needBytes) break
            ids += r.id
            freed += r.sizeBytes
        }
        return if (usable + freed >= needBytes) SpaceDecision.DeleteThenProceed(ids) else refuse
    }

    /**
     * Periodic check while a capture runs. [freeBytes] is measured now; [expectedBytesUntilNextCheck]
     * is what the capture will write before the next check.
     */
    fun duringCapture(
        freeBytes: Long,
        expectedBytesUntilNextCheck: Long,
        policy: LowSpacePolicy,
        deletable: List<Recording>,
    ): CaptureSpaceDecision = when (val d = check(freeBytes, expectedBytesUntilNextCheck, policy, deletable)) {
        SpaceDecision.Proceed -> CaptureSpaceDecision.Continue
        is SpaceDecision.DeleteThenProceed -> CaptureSpaceDecision.DeleteThenContinue(d.ids)
        is SpaceDecision.Refuse -> CaptureSpaceDecision.Stop(d.problem)
    }
}
