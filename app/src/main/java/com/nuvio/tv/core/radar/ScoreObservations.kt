package com.nuvio.tv.core.radar

/**
 * Where fetched fixtures and live rows are recorded for the score-delay buffer, and what decides
 * "was this game already over the first time we saw it". Owned by [RadarRepository]; recording
 * happens where a fetch result lands, never in a Composable.
 */
class ScoreObservations(
    val buffer: DelayedValueBuffer<String, RadarLiveScore> = DelayedValueBuffer(),
) {
    private val seen = HashSet<String>()
    private val finishedFirst = HashSet<String>()

    /** Fixtures first (first-sight bookkeeping), then the live rows of the same response. */
    @Synchronized
    fun note(fixtures: Collection<RadarFixture>, liveRows: Collection<RadarLiveScore>, nowMs: Long) {
        val liveIds = liveRows.mapNotNullTo(HashSet()) { it.eventId }
        for (fx in fixtures) {
            val id = fx.id ?: continue
            if (seen.add(id) && fx.isFinishedOrOff) finishedFirst.add(id)
            // Every game not already over at first sight is delayed, whichever feed carried its score:
            // the fixture's own score is one more observation (a sport outside the livescore feed, or a
            // game the feed missed). A live row of the same response wins: it carries the minute too.
            if (id !in finishedFirst && id !in liveIds) fixtureObservation(fx)?.let { buffer.record(id, it, nowMs) }
        }
        for (row in liveRows) {
            val id = row.eventId ?: continue
            seen.add(id)
            buffer.record(id, row, nowMs)
        }
    }

    /**
     * Disk-cache load: a game already over is old news (never delayed); every other cached score is buffered with
     * the load time, so after the delay it shows the cached value instead of "-" until the next fetch.
     */
    @Synchronized
    fun noteCached(fixtures: Collection<RadarFixture>, liveRows: Collection<RadarLiveScore>, nowMs: Long) {
        for (fx in fixtures) {
            val id = fx.id ?: continue
            if (seen.add(id) && fx.isFinishedOrOff) finishedFirst.add(id)
        }
        note(fixtures, liveRows, nowMs)
    }

    @Synchronized
    fun finishedAtFirstSight(id: String?): Boolean = id != null && id in finishedFirst

    fun scoreView(settings: ScoreSpoilerSettings, eventId: String?, observed: RadarLiveScore?, nowMs: Long): ScoreView =
        ScoreSpoilerPolicy.scoreFor(settings, eventId, observed, finishedAtFirstSight(eventId), buffer, nowMs)

    /** Fixture-aware form: a fixture's own score counts as an observation too (see [fixtureObservation]). */
    fun scoreView(settings: ScoreSpoilerSettings, fixture: RadarFixture, liveRow: RadarLiveScore?, nowMs: Long): ScoreView =
        scoreView(settings, fixture.id, liveRow ?: fixtureObservation(fixture), nowMs)

    private fun fixtureObservation(fx: RadarFixture): RadarLiveScore? {
        val id = fx.id ?: return null
        if (fx.homeScore.isNullOrBlank() && fx.awayScore.isNullOrBlank()) return null
        // Status only distinguishes "final" so an unchanged score dedupes in the buffer.
        return RadarLiveScore(eventId = id, status = if (fx.isFinishedOrOff) "FT" else null, homeScore = fx.homeScore, awayScore = fx.awayScore)
    }

    fun nextChangeAtMs(nowMs: Long, delayMs: Long): Long? = buffer.nextChangeAtMs(nowMs, delayMs)
}
