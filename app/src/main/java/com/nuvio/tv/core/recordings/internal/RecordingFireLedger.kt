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

class RecordingFireLedger(initial: FireLedgerState = FireLedgerState()) {
    sealed interface Claim {
        data class Claimed(val token: String) : Claim
        data object AlreadyRunning : Claim
        data object AlreadyDone : Claim
    }
    fun claim(scheduleId: String, nowElapsedMs: Long): Claim = TODO()
    fun release(token: String, outcome: FireOutcome, problem: RecordingProblem? = null): Boolean = TODO()
    fun recoverAfterRestart(): List<String> = TODO()
    fun forget(scheduleId: String): Unit = TODO()
    fun stateOf(scheduleId: String): FireState? = TODO()
    fun snapshot(): FireLedgerState = TODO()
}
