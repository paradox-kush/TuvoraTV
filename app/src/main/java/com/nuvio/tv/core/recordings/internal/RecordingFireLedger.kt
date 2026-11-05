package com.nuvio.tv.core.recordings.internal

import com.nuvio.tv.core.recordings.api.RecordingProblem
import kotlinx.serialization.Serializable

@Serializable
enum class FireState { RUNNING, DONE, FAILED }

@Serializable
data class FireEntry(
    val scheduleId: String,
    val state: FireState,
    val token: String,
    /** The monotonic clock when claimed, for diagnostics only: it means nothing after a restart. */
    val claimedAtElapsedMs: Long,
    val problem: RecordingProblem? = null,
)

/** What the ledger persists, so a claim survives a process death. */
@Serializable
data class FireLedgerState(val entries: List<FireEntry> = emptyList(), val nextSeq: Long = 1)

enum class FireOutcome { DONE, FAILED }

/**
 * Makes a fire idempotent (DDIA ch. 11), keyed on the schedule id. A re-delivered alarm, a boot
 * re-arm, an overdue sweep and a manual "start now" all call [claim]; exactly one gets a token and
 * the others are told it is already running or done. Pure state machine: the platform persists
 * [snapshot] and rebuilds it with the constructor.
 */
class RecordingFireLedger(initial: FireLedgerState = FireLedgerState()) {

    sealed interface Claim {
        data class Claimed(val token: String) : Claim
        data object AlreadyRunning : Claim
        data object AlreadyDone : Claim
    }

    private val entries = LinkedHashMap<String, FireEntry>().also { m -> initial.entries.forEach { m[it.scheduleId] = it } }
    private var nextSeq = initial.nextSeq

    fun claim(scheduleId: String, nowElapsedMs: Long): Claim {
        when (entries[scheduleId]?.state) {
            FireState.RUNNING -> return Claim.AlreadyRunning
            FireState.DONE -> return Claim.AlreadyDone
            // FAILED (or never seen): a retry is a new claim.
            else -> Unit
        }
        val token = "$scheduleId#${nextSeq++}"
        entries[scheduleId] = FireEntry(scheduleId, FireState.RUNNING, token, nowElapsedMs)
        return Claim.Claimed(token)
    }

    /**
     * Ends the claim held by [token]. A stale token (the claim was recovered or a newer one was
     * made) changes nothing and returns false, so a late finish cannot clobber a newer capture.
     */
    fun release(token: String, outcome: FireOutcome, problem: RecordingProblem? = null): Boolean {
        val entry = entries.values.firstOrNull { it.token == token && it.state == FireState.RUNNING } ?: return false
        entries[entry.scheduleId] = when (outcome) {
            FireOutcome.DONE -> entry.copy(state = FireState.DONE, problem = null)
            FireOutcome.FAILED -> entry.copy(state = FireState.FAILED, problem = problem ?: RecordingProblem.UNKNOWN)
        }
        return true
    }

    /**
     * Call once at process start: a RUNNING claim then belongs to a process that is gone. Each
     * becomes FAILED(STALLED) so it can be claimed again; returns their ids for the retry policy.
     */
    fun recoverAfterRestart(): List<String> {
        val recovered = ArrayList<String>()
        for ((id, e) in entries.entries.toList()) {
            if (e.state == FireState.RUNNING) {
                entries[id] = e.copy(state = FireState.FAILED, problem = RecordingProblem.STALLED)
                recovered += id
            }
        }
        return recovered
    }

    /** Drops a schedule's history (cancelled or deleted), so an id reused later starts clean. */
    fun forget(scheduleId: String) {
        entries.remove(scheduleId)
    }

    fun stateOf(scheduleId: String): FireState? = entries[scheduleId]?.state

    fun snapshot(): FireLedgerState = FireLedgerState(entries.values.toList(), nextSeq)
}
