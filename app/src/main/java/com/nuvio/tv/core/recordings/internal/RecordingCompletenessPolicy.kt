package com.nuvio.tv.core.recordings.internal

/** Judges a stream that ended cleanly without saying how long it was (chunked, no Content-Length). */
internal object RecordingCompletenessPolicy {
    private const val MIN_FRACTION_DENOMINATOR = 10L

    /**
     * True when the end cannot be believed: nothing declared the length, an estimate exists, and far
     * less than a tenth of it arrived. The estimate is a bitrate guess, so only a gross shortfall counts.
     */
    fun endedTooEarly(writtenBytes: Long, declaredLength: Long?, estimatedBytes: Long): Boolean =
        declaredLength == null && estimatedBytes > 0 && writtenBytes < estimatedBytes / MIN_FRACTION_DENOMINATOR
}
