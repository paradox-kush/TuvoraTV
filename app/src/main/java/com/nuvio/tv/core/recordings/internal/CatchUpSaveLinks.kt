package com.nuvio.tv.core.recordings.internal

import com.nuvio.tv.core.recordings.api.RecordingChannel
import com.nuvio.tv.core.recordings.api.RecordingProblem

object CatchUpSaveLinks {
    const val RESUME_OVERLAP_MS = 30_000L
    fun candidates(channel: RecordingChannel, fromMs: Long, toMs: Long, nowMs: Long): List<String> = TODO()
    fun resumeCandidates(channel: RecordingChannel, fromMs: Long, doneMs: Long, toMs: Long, nowMs: Long): List<String> = TODO()
    fun isProgressive(url: String): Boolean = TODO()
    fun problemFor(candidates: List<String>): RecordingProblem? = TODO()
}
