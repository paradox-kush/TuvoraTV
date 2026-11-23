package com.nuvio.tv.core.radar

/** How a pulled radar_prefs row lands on the local prefs. Pure, so the sync service stays untestable-free. */
object RadarPrefsMerge {
    @Suppress("LongParameterList")
    fun merge(
        local: RadarPrefs,
        featuredEventId: String,
        optInState: String,
        promoDismissed: Boolean,
        scoresShown: Boolean?,
        scoreDelayS: Int?,
        localSpoilerChangePending: Boolean,
    ): RadarPrefs = RadarPrefs(
        featuredEventId = featuredEventId,
        optInState = optInState,
        promoDismissed = promoDismissed,
        // A column the server row lacks, or a local change not pushed yet, keeps the local value.
        scoresShown = if (localSpoilerChangePending) local.scoresShown else scoresShown ?: local.scoresShown,
        scoreDelayS = if (localSpoilerChangePending) local.scoreDelayS
        else ScoreSpoilerSettings.sanitizeDelay(scoreDelayS ?: local.scoreDelayS),
    )
}

/**
 * Tracks whether a local hide/delay change has not reached the server yet. A pull must not overwrite
 * such a change, and a change made mid-pull must still be pushed afterwards.
 */
class SpoilerPushTracker {
    private var changeRev = 0L
    private var pushedRev = 0L

    @get:Synchronized
    val pending: Boolean get() = changeRev != pushedRev

    @Synchronized fun noteChange() { changeRev++ }

    /** Token to hand to [pushSucceeded]: the change revision the push is about to carry. */
    @Synchronized fun beginPush(): Long = changeRev

    @Synchronized fun pushSucceeded(token: Long) { if (token > pushedRev) pushedRev = token }
}
