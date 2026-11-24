package com.nuvio.tv.core.radar

/** How a pulled radar_prefs row lands on the local prefs. Pure. */
object RadarPrefsMerge {
    fun merge(
        local: RadarPrefs,
        featuredEventId: String,
        optInState: String,
        promoDismissed: Boolean,
        scoresShown: Boolean?,
        scoreDelayS: Int?,
    ): RadarPrefs = local.copy(
        featuredEventId = featuredEventId,
        optInState = optInState,
        promoDismissed = promoDismissed,
        // An unpushed local change (spoilerDirty) or a column the row lacks keeps the local value.
        scoresShown = if (local.spoilerDirty) local.scoresShown else scoresShown ?: local.scoresShown,
        scoreDelayS = if (local.spoilerDirty) local.scoreDelayS
        else ScoreSpoilerSettings.sanitizeDelay(scoreDelayS ?: local.scoreDelayS),
    )
}

/** The dirty-flag protocol for the hide/delay setting (persisted in [RadarPrefs.spoilerDirty], no tokens). */
object SpoilerSync {
    fun markChanged(p: RadarPrefs, shown: Boolean = p.scoresShown, delayS: Int = p.scoreDelayS): RadarPrefs =
        p.copy(scoresShown = shown, scoreDelayS = ScoreSpoilerSettings.sanitizeDelay(delayS), spoilerDirty = true)

    /** After a successful push of ([pushedShown], [pushedDelayS]): clear dirty only if nothing changed since. */
    fun clearIfPushed(current: RadarPrefs, pushedShown: Boolean, pushedDelayS: Int): RadarPrefs =
        if (current.scoresShown == pushedShown &&
            ScoreSpoilerSettings.sanitizeDelay(current.scoreDelayS) == ScoreSpoilerSettings.sanitizeDelay(pushedDelayS)
        ) current.copy(spoilerDirty = false) else current

    /** After a pull is applied: a setting still dirty (failed/offline push, or a toggle during the pull) goes out. */
    fun shouldPushAfterPull(applied: RadarPrefs): Boolean = applied.spoilerDirty
}
