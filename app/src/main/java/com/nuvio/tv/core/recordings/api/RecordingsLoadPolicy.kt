package com.nuvio.tv.core.recordings.api

/** One try at reading the persisted recordings. [failed] = the read threw. */
data class RecordingsLoadAttempt(val startedAtMs: Long, val failed: Boolean = false)

sealed interface RecordingsView {
    /** Still reading; the screen ends the wait itself at [deadlineAtMs]. */
    data class Loading(val deadlineAtMs: Long) : RecordingsView
    data object Ready : RecordingsView
    data class Failed(val timedOut: Boolean) : RecordingsView
}

/**
 * Every load ends (repo-root CLAUDE.md, "every loading state has a deadline and a terminal outcome"):
 * loaded, or failed with Retry. A store that cannot be read is never shown as "no recordings".
 */
object RecordingsLoadPolicy {
    const val DEADLINE_MS = 10_000L

    fun viewAt(loaded: Boolean, attempt: RecordingsLoadAttempt, nowMs: Long): RecordingsView {
        if (loaded) return RecordingsView.Ready
        if (attempt.failed) return RecordingsView.Failed(timedOut = false)
        val deadline = attempt.startedAtMs + DEADLINE_MS
        return if (nowMs >= deadline) RecordingsView.Failed(timedOut = true) else RecordingsView.Loading(deadline)
    }
}
