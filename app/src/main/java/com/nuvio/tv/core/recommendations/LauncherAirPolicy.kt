package com.nuvio.tv.core.recommendations

/**
 * Whether an episode has aired, as far as the launcher is concerned.
 *
 * The Continue Watching cache freezes `hasAired` when it is written. An episode that airs while the
 * app is closed would stay hidden from the TV home screen until the app was next opened, so the
 * launcher re-evaluates against the clock using the release timestamp the cache already stores.
 * No network: the refresh job keeps reading only the on-disk cache.
 *
 * Monotonic: something the cache says has aired never un-airs.
 */
object LauncherAirPolicy {

    private const val SIXTY_DAYS_MS = 60L * 24 * 60 * 60 * 1000

    fun hasAired(releaseTimestamp: Long?, cachedHasAired: Boolean, nowMs: Long): Boolean {
        if (cachedHasAired) return true
        return releaseTimestamp != null && nowMs >= releaseTimestamp
    }

    /**
     * The in-app "new episode" rule (aired, released after the viewer last watched, within 60 days),
     * so an episode that aired while the app was closed is published as NEW, not as a plain next-up.
     */
    fun isReleaseAlert(
        cachedIsReleaseAlert: Boolean,
        aired: Boolean,
        releaseTimestamp: Long?,
        lastWatchedMs: Long,
        nowMs: Long
    ): Boolean {
        if (cachedIsReleaseAlert) return true
        if (!aired || releaseTimestamp == null) return false
        return releaseTimestamp > lastWatchedMs && nowMs - releaseTimestamp < SIXTY_DAYS_MS
    }
}
