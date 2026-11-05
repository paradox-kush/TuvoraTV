package com.nuvio.tv.core.recordings.internal

class RecordingStallPolicy(startElapsedMs: Long, private val stallMs: Long = STALL_MS) {
    fun onProgress(elapsedMs: Long): Unit = TODO()
    fun isStalled(elapsedMs: Long): Boolean = TODO()
    companion object { const val STALL_MS = 30_000L }
}
