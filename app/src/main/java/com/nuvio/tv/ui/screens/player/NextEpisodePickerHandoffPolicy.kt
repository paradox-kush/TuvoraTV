package com.nuvio.tv.ui.screens.player

/**
 * When the next-episode flow should skip its source search and open the stream picker at once.
 */
internal object NextEpisodePickerHandoffPolicy {

    /**
     * A binge-group-only search (MANUAL mode, "Prefer Binge Group" on, auto-play off) can only ever
     * pick a stream from the same binge group. With no group to follow — IPTV streams carry none —
     * it is certain to end in the picker, so the "Finding source…" wait is pure delay (UX25).
     */
    fun skipSearch(bingeGroupOnly: Boolean, currentBingeGroup: String?): Boolean =
        bingeGroupOnly && currentBingeGroup.isNullOrBlank()
}
