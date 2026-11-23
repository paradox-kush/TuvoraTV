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
        for (fx in fixtures) {
            val id = fx.id ?: continue
            if (seen.add(id) && fx.isFinishedOrOff) finishedFirst.add(id)
            // A game we watched live that now reads final: its final score is delayed like the rest,
            // so record it as one more snapshot instead of letting the fixture's score jump ahead.
            if (fx.isFinishedOrOff && id !in finishedFirst && buffer.hasKey(id) &&
                (!fx.homeScore.isNullOrBlank() || !fx.awayScore.isNullOrBlank())
            ) {
                buffer.record(id, RadarLiveScore(eventId = id, status = "FT", homeScore = fx.homeScore, awayScore = fx.awayScore), nowMs)
            }
        }
        for (row in liveRows) {
            val id = row.eventId ?: continue
            seen.add(id)
            buffer.record(id, row, nowMs)
        }
    }

    /** Marks fixtures loaded from the disk cache as seen (old news) without buffering their scores. */
    @Synchronized
    fun noteCached(fixtures: Collection<RadarFixture>) {
        for (fx in fixtures) {
            val id = fx.id ?: continue
            if (seen.add(id) && fx.isFinishedOrOff) finishedFirst.add(id)
        }
    }

    @Synchronized
    fun finishedAtFirstSight(id: String?): Boolean = id != null && id in finishedFirst

    fun scoreView(settings: ScoreSpoilerSettings, eventId: String?, observed: RadarLiveScore?, nowMs: Long): ScoreView =
        ScoreSpoilerPolicy.scoreFor(settings, eventId, observed, finishedAtFirstSight(eventId), buffer, nowMs)

    fun nextChangeAtMs(nowMs: Long, delayMs: Long): Long? = buffer.nextChangeAtMs(nowMs, delayMs)
}
