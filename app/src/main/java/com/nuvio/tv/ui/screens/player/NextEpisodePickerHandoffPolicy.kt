package com.nuvio.tv.ui.screens.player

/**
 * When the next-episode flow should skip its source search, and whether the stream picker it falls
 * back to may reuse what that search already collected.
 */
internal object NextEpisodePickerHandoffPolicy {

    /**
     * A binge-group-only search (MANUAL mode, "Prefer Binge Group" on, auto-play off) can only ever
     * pick a stream from the same binge group. With no group to follow — IPTV streams carry none —
     * it is certain to end in the picker, so the "Finding source…" wait is pure delay (UX25).
     */
    fun skipSearch(bingeGroupOnly: Boolean, currentBingeGroup: String?): Boolean =
        bingeGroupOnly && currentBingeGroup.isNullOrBlank()

    /**
     * After a search that picked nothing, the picker would ask every source again. Reuse the
     * results when the search actually finished and found streams (UX26); refetch when it was cut
     * short, found nothing, or its chosen stream failed to resolve (links may be stale). A source
     * that errored stays errored in the picker; its refresh button retries on demand.
     */
    fun reuseSearchResults(searchComplete: Boolean, hasStreams: Boolean, selectedStreamFailed: Boolean): Boolean =
        searchComplete && hasStreams && !selectedStreamFailed
}
