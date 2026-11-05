package com.nuvio.tv.core.recordings.api

/**
 * Which guide programmes carry a REC badge. Derived ONCE from the state (not per cell), as
 * channel key -> programme start times, so a guide row does one map lookup and each cell a set lookup.
 */
object RecordingGuideKeys {
    /** Scheduled and recording programmes; DONE / FAILED rows are not "upcoming". */
    fun startsByChannel(state: RecordingsState): Map<String, Set<Long>> = TODO("wave 3")
}
