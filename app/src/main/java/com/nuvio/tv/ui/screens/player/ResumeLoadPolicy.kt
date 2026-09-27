package com.nuvio.tv.ui.screens.player

/**
 * A resume opens a file mid-way, which costs the provider extra ranged requests (container index at
 * the end of the file, then the jump to the saved position). Some IPTV panels answer each of those in
 * tens of seconds, so a resume can sit on the loading screen for a minute or more while a start from
 * 0:00 would play almost at once (seen on a real panel: ~1 min on iPad, ~1m46s on Onn). The loading
 * screen says it is resuming, and after [START_OVER_OFFER_AFTER_MS] without a first frame it offers
 * "Start from beginning" so the viewer is never left waiting on a spinner with no way out.
 */
internal object ResumeLoadPolicy {
    const val START_OVER_OFFER_AFTER_MS = 15_000L

    fun isResumeLoad(initialPositionMs: Long, isLive: Boolean): Boolean =
        !isLive && initialPositionMs > 0L

    fun offerStartOver(isResumeLoad: Boolean, firstFrameShown: Boolean, loadingForMs: Long): Boolean =
        isResumeLoad && !firstFrameShown && loadingForMs >= START_OVER_OFFER_AFTER_MS
}
