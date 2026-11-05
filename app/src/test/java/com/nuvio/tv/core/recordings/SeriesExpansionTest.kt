package com.nuvio.tv.core.recordings

import com.nuvio.tv.core.recordings.api.CaptureMode
import com.nuvio.tv.core.recordings.api.RecordingStatus
import com.nuvio.tv.core.recordings.api.ListingInfo
import com.nuvio.tv.core.recordings.internal.SeriesExpansion
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class SeriesExpansionTest {

    private val r = rule("r1", "The Show")

    private fun listing(
        start: Long,
        title: String = "The Show",
        desc: String? = null,
        ep: String? = null,
    ) = ListingInfo(title, desc, start, start + HOUR, ep)

    private fun expand(
        listings: List<ListingInfo>,
        schedules: List<com.nuvio.tv.core.recordings.api.RecordingSchedule> = emptyList(),
        recordings: List<com.nuvio.tv.core.recordings.api.Recording> = emptyList(),
        mode: (Long, Long) -> CaptureMode? = { _, _ -> CaptureMode.LIVE },
    ) = SeriesExpansion.expand(r, listings, schedules, recordings, NOW, 2 * MIN, 5 * MIN, mode)

    // --- titles ------------------------------------------------------------------------------

    @Test
    fun `titles fold case punctuation and NEW or LIVE marks`() {
        val n = SeriesExpansion::normalizeTitle
        assertEquals(n("The Show"), n("THE SHOW"))
        assertEquals(n("The Show"), n("The Show!"))
        assertEquals(n("The Show"), n("The Show (New)"))
        assertEquals(n("The Show"), n("The Show [LIVE]"))
        assertEquals(n("The Show"), n("The Show - NEW"))
        assertEquals(n("The Show"), n("The  Show:"))
        assertEquals(n("The Show"), n("The Show (R)"))
        assertEquals("the show", n("The Show"))
    }

    @Test
    fun `different titles stay different`() {
        assertTrue(SeriesExpansion.normalizeTitle("The Show") != SeriesExpansion.normalizeTitle("The Shows"))
        assertTrue(SeriesExpansion.normalizeTitle("Live at the Apollo") != SeriesExpansion.normalizeTitle("at the Apollo"))
    }

    // --- expansion -----------------------------------------------------------------------------

    @Test
    fun `future airings of the title become schedules with deterministic ids`() {
        val start = NOW + DAY
        val out = expand(listOf(listing(start, ep = "1.4."), listing(start + DAY, title = "Other Show"), listing(start + 2 * DAY, title = "THE SHOW (New)", ep = "1.5.")))
        assertEquals(2, out.size)
        val s = out[0]
        assertEquals("r1:ch1:$start", s.id)
        assertEquals("r1", s.seriesRuleId)
        assertEquals(r.channel, s.channel)
        assertEquals("S02E05", s.episode)
        assertEquals(2 * MIN, s.preMs)
        assertEquals(5 * MIN, s.postMs)
        assertEquals(CaptureMode.LIVE, s.mode)
        assertEquals(start, s.programmeStartMs)
        assertEquals(start + HOUR, s.programmeEndMs)
    }

    @Test
    fun `expanding twice over the same guide adds nothing the second time`() {
        val listings = listOf(listing(NOW + DAY, ep = "1.0."), listing(NOW + 2 * DAY, ep = "1.1."))
        val first = expand(listings)
        val second = expand(listings, schedules = first)
        assertEquals(2, first.size)
        assertTrue(second.isEmpty())
    }

    @Test
    fun `an airing already scheduled by channel and start is skipped even under another id`() {
        val start = NOW + DAY
        val existing = schedule("manual-1", start = start, ch = r.channel)
        assertTrue(expand(listOf(listing(start)), schedules = listOf(existing)).isEmpty())
    }

    @Test
    fun `an airing whose slot is already recorded is skipped`() {
        val start = NOW + DAY
        val rec = recording("rec", start = start, channelKey = "ch1", status = RecordingStatus.DONE)
        assertTrue(expand(listOf(listing(start)), recordings = listOf(rec)).isEmpty())
    }

    @Test
    fun `a failed or cancelled recording of the slot does not block a new try`() {
        val start = NOW + DAY
        for (s in listOf(RecordingStatus.FAILED, RecordingStatus.CANCELLED)) {
            val rec = recording("rec", start = start, status = s)
            assertEquals("status $s", 1, expand(listOf(listing(start)), recordings = listOf(rec)).size)
        }
    }

    @Test
    fun `a repeat of a scheduled episode is skipped`() {
        val first = expand(listOf(listing(NOW + DAY, ep = "1.4.")))
        val out = expand(listOf(listing(NOW + 3 * DAY, ep = "1.4.")), schedules = first)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `a repeat of a recorded episode is skipped`() {
        val rec = recording("rec", start = NOW - 7 * DAY, seriesRuleId = "r1", title = "The Show", episode = "S02E05")
        assertTrue(expand(listOf(listing(NOW + DAY, ep = "1.4.")), recordings = listOf(rec)).isEmpty())
    }

    @Test
    fun `a recording of the same title on the channel counts even without the rule id`() {
        val rec = recording("rec", start = NOW - 7 * DAY, title = "The Show (New)", episode = "S02E05")
        assertTrue(expand(listOf(listing(NOW + DAY, ep = "1.4.")), recordings = listOf(rec)).isEmpty())
    }

    @Test
    fun `a different episode is kept`() {
        val rec = recording("rec", start = NOW - 7 * DAY, seriesRuleId = "r1", episode = "S02E05")
        assertEquals(1, expand(listOf(listing(NOW + DAY, ep = "1.5.")), recordings = listOf(rec)).size)
    }

    @Test
    fun `two airings of one episode in the same guide keep the earliest`() {
        val out = expand(listOf(listing(NOW + 3 * DAY, ep = "1.4."), listing(NOW + DAY, ep = "1.4.")))
        assertEquals(listOf(NOW + DAY), out.map { it.programmeStartMs })
    }

    @Test
    fun `the episode key can come from the title or description when the guide has none`() {
        val out = expand(listOf(listing(NOW + DAY, desc = "S03E07 The return"), listing(NOW + 2 * DAY, desc = "S03E07 The return")))
        assertEquals(1, out.size)
        assertEquals("S03E07", out[0].episode)
    }

    @Test
    fun `without an episode key the same description is a repeat`() {
        val out = expand(listOf(listing(NOW + DAY, desc = "The crew finds a map."), listing(NOW + 2 * DAY, desc = "the crew finds a MAP!")))
        assertEquals(1, out.size)
        assertEquals(null, out[0].episode)
    }

    @Test
    fun `without a key or a description every airing is kept`() {
        assertEquals(2, expand(listOf(listing(NOW + DAY), listing(NOW + 2 * DAY))).size)
    }

    @Test
    fun `without a key different descriptions are different programmes`() {
        assertEquals(2, expand(listOf(listing(NOW + DAY, desc = "A"), listing(NOW + 2 * DAY, desc = "B"))).size)
    }

    @Test
    fun `live capture skips airings that have started`() {
        val out = expand(listOf(listing(NOW - HOUR), listing(NOW), listing(NOW + 1)))
        assertEquals(listOf(NOW + 1), out.map { it.programmeStartMs })
    }

    @Test
    fun `catch-up allows airings in the past that the mode function accepts`() {
        val past = listing(NOW - 5 * HOUR)
        val out = expand(listOf(past)) { _, _ -> CaptureMode.CATCH_UP }
        assertEquals(1, out.size)
        assertEquals(CaptureMode.CATCH_UP, out[0].mode)
    }

    @Test
    fun `an airing that cannot be captured here is skipped`() {
        val out = expand(listOf(listing(NOW + DAY), listing(NOW + 2 * DAY))) { start, _ -> if (start == NOW + DAY) null else CaptureMode.LIVE }
        assertEquals(listOf(NOW + 2 * DAY), out.map { it.programmeStartMs })
    }

    @Test
    fun `listings are scheduled in time order whatever order the guide gave`() {
        val out = expand(listOf(listing(NOW + 3 * DAY), listing(NOW + DAY), listing(NOW + 2 * DAY)))
        assertEquals(listOf(NOW + DAY, NOW + 2 * DAY, NOW + 3 * DAY), out.map { it.programmeStartMs })
    }

    // --- keep last -------------------------------------------------------------------------------

    @Test
    fun `keep last deletes everything past the newest N by programme start`() {
        val rule = rule("r1", keepLast = 2)
        val recs = listOf(
            recording("old", start = NOW - 30 * DAY, seriesRuleId = "r1"),
            recording("mid", start = NOW - 20 * DAY, seriesRuleId = "r1"),
            recording("new", start = NOW - 10 * DAY, seriesRuleId = "r1"),
            recording("older", start = NOW - 40 * DAY, seriesRuleId = "r1"),
        )
        assertEquals(listOf("old", "older"), SeriesExpansion.keepLastToDelete(rule, recs))
    }

    @Test
    fun `keep last of zero keeps everything`() {
        val recs = listOf(recording("a", seriesRuleId = "r1"), recording("b", start = NOW - 9 * DAY, seriesRuleId = "r1"))
        assertTrue(SeriesExpansion.keepLastToDelete(rule("r1", keepLast = 0), recs).isEmpty())
    }

    @Test
    fun `keep last ignores other series and unfinished recordings`() {
        val recs = listOf(
            recording("mine-new", start = NOW - 1 * DAY, seriesRuleId = "r1"),
            recording("mine-running", start = NOW, status = RecordingStatus.RECORDING, seriesRuleId = "r1"),
            recording("mine-old", start = NOW - 9 * DAY, seriesRuleId = "r1"),
            recording("other", start = NOW - 99 * DAY, seriesRuleId = "r2"),
            recording("single", start = NOW - 99 * DAY),
        )
        assertEquals(listOf("mine-old"), SeriesExpansion.keepLastToDelete(rule("r1", keepLast = 1), recs))
    }

    @Test
    fun `keep last has nothing to delete when there are fewer than N`() {
        val recs = listOf(recording("a", seriesRuleId = "r1"))
        assertTrue(SeriesExpansion.keepLastToDelete(rule("r1", keepLast = 3), recs).isEmpty())
    }
}
