package com.nuvio.tv.core.recordings.internal

/**
 * A capture that stops receiving bytes is stalled after [STALL_MS]. Only the monotonic elapsed
 * clock is read (a wall-clock change, such as a timezone or NTP jump, must never fake a stall).
 */
class RecordingStallPolicy(startElapsedMs: Long, private val stallMs: Long = STALL_MS) {
    private var lastProgressMs = startElapsedMs

    fun onProgress(elapsedMs: Long) {
        if (elapsedMs > lastProgressMs) lastProgressMs = elapsedMs
    }

    fun isStalled(elapsedMs: Long): Boolean = elapsedMs - lastProgressMs >= stallMs

    companion object {
        const val STALL_MS = 30_000L
    }
}
