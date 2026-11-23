package com.nuvio.tv.core.radar

/** What a score slot renders. [ScoreShown.value] null = no live row: fall back to the fixture's own score. */
sealed interface ScoreView {
    data class ScoreShown(val value: RadarLiveScore?) : ScoreView
    data object ScoreHidden : ScoreView
    /** Delay on and nothing old enough yet: the UI shows "–". */
    data object ScorePending : ScoreView
}

/**
 * The one decision point for "may this score be shown right now?". Future features that surface a
 * score (alerts, storylines, strips) must ask here.
 */
object ScoreSpoilerPolicy {
    fun scoreFor(
        settings: ScoreSpoilerSettings,
        eventId: String?,
        observed: RadarLiveScore?,
        fixtureFinishedAtFirstSight: Boolean,
        buffer: DelayedValueBuffer<String, RadarLiveScore>,
        nowMs: Long,
    ): ScoreView {
        if (!settings.shown) return ScoreView.ScoreHidden
        val delayMs = settings.delayMs
        if (delayMs <= 0L || eventId == null || fixtureFinishedAtFirstSight) return ScoreView.ScoreShown(observed)
        // Never seen live: nothing to run ahead of (an upcoming game, or a result with no live row).
        if (!buffer.hasKey(eventId)) return ScoreView.ScoreShown(observed)
        val old = buffer.valueAt(eventId, nowMs, delayMs) ?: return ScoreView.ScorePending
        return ScoreView.ScoreShown(old)
    }

    /** Spoiler-bearing game-page parts (scorers, last play, win chance, box score...) are hidden. */
    fun hidesDetails(settings: ScoreSpoilerSettings): Boolean = !settings.shown
}
