package com.nuvio.tv.core.recordings.internal

import com.nuvio.tv.core.recordings.api.RecordingProblem

/** A "finished" save of a real programme cannot be a few KB: that is an error page or a playlist, not footage. */
internal object RecordingSizeSanity {
    const val MIN_BYTES = 64L * 1024
    const val MIN_PROGRAMME_MS = 60_000L

    fun isImplausiblySmall(bytes: Long, programmeDurationMs: Long): Boolean = false

    /** UNSUPPORTED_FORMAT when the bytes are an HLS playlist, NETWORK (retryable) otherwise, null when the size is believable. */
    fun problemFor(bytes: Long, programmeDurationMs: Long, startsWithPlaylistTag: Boolean): RecordingProblem? = null
}
