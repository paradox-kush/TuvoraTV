package com.nuvio.tv.core.radar

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreSpoilerPolicyTest {
    private fun live(h: String, a: String) = RadarLiveScore(eventId = "e1", status = "1H", homeScore = h, awayScore = a)
    private val show = ScoreSpoilerSettings()
    private fun delayed(s: Int) = ScoreSpoilerSettings(shown = true, delaySeconds = s)

    @Test
    fun `defaults show the observed score`() {
        val buf = DelayedValueBuffer<String, RadarLiveScore>()
        val r = ScoreSpoilerPolicy.scoreFor(show, "e1", live("1", "0"), false, buf, 1_000L)
        assertEquals("defaults show", ScoreView.ScoreShown(live("1", "0")), r)
    }

    @Test
    fun `hidden hides live and final`() {
        val buf = DelayedValueBuffer<String, RadarLiveScore>()
        val hidden = ScoreSpoilerSettings(shown = false)
        assertEquals("live", ScoreView.ScoreHidden, ScoreSpoilerPolicy.scoreFor(hidden, "e1", live("1", "0"), false, buf, 1L))
        assertEquals("final", ScoreView.ScoreHidden, ScoreSpoilerPolicy.scoreFor(hidden, "e1", null, true, buf, 1L))
        assertTrue("hides details", ScoreSpoilerPolicy.hidesDetails(hidden))
        assertFalse("shown keeps details", ScoreSpoilerPolicy.hidesDetails(show))
    }

    @Test
    fun `delay shows the older value not the newer`() {
        val buf = DelayedValueBuffer<String, RadarLiveScore>()
        buf.record("e1", live("0", "0"), 0L)
        buf.record("e1", live("1", "0"), 20_000L)
        val r = ScoreSpoilerPolicy.scoreFor(delayed(30), "e1", live("1", "0"), false, buf, 40_000L)
        assertEquals("30 s old value", ScoreView.ScoreShown(live("0", "0")), r)
    }

    @Test
    fun `delay with nothing old enough is pending`() {
        val buf = DelayedValueBuffer<String, RadarLiveScore>()
        buf.record("e1", live("1", "0"), 10_000L)
        val r = ScoreSpoilerPolicy.scoreFor(delayed(30), "e1", live("1", "0"), false, buf, 20_000L)
        assertEquals("pending", ScoreView.ScorePending, r)
    }

    @Test
    fun `already final at first sight is not delayed`() {
        val buf = DelayedValueBuffer<String, RadarLiveScore>()
        val r = ScoreSpoilerPolicy.scoreFor(delayed(60), "e1", live("2", "1"), true, buf, 5_000L)
        assertEquals("old news", ScoreView.ScoreShown(live("2", "1")), r)
    }

    @Test
    fun `delay zero is newest`() {
        val buf = DelayedValueBuffer<String, RadarLiveScore>()
        buf.record("e1", live("0", "0"), 0L)
        buf.record("e1", live("1", "0"), 1_000L)
        assertEquals("newest", live("1", "0"), buf.valueAt("e1", 1_000L, 0L))
    }

    @Test
    fun `sanitizeDelay and nextDelay`() {
        assertEquals("45 invalid", 0, ScoreSpoilerSettings.sanitizeDelay(45))
        assertEquals("30 ok", 30, ScoreSpoilerSettings.sanitizeDelay(30))
        assertEquals("negative", 0, ScoreSpoilerSettings.sanitizeDelay(-5))
        assertEquals(listOf(0, 15, 30, 60), ScoreSpoilerSettings.ALLOWED_DELAYS)
        var d = 0
        val seen = mutableListOf<Int>()
        repeat(5) { d = ScoreSpoilerSettings.nextDelay(d); seen += d }
        assertEquals("cycle", listOf(15, 30, 60, 0, 15), seen)
        assertEquals("unknown restarts", 15, ScoreSpoilerSettings.nextDelay(45))
    }

    @Test
    fun `buffer dedupes identical values and stays bounded`() {
        val buf = DelayedValueBuffer<String, Int>()
        buf.record("k", 1, 0L); buf.record("k", 1, 1_000L); buf.record("k", 1, 2_000L)
        assertEquals("dedupe", 1, buf.snapshotCount("k"))
        for (i in 2..200) buf.record("k", i, i * 1_000L)
        assertTrue("bounded by time (60 s window + anchor)", buf.snapshotCount("k") <= 62)
        assertEquals("newest kept", 200, buf.valueAt("k", 200_000L, 0L))
        assertEquals("still serves a 60 s delay after overflow", 140, buf.valueAt("k", 200_000L, 60_000L))
        assertEquals("and a 15 s delay", 185, buf.valueAt("k", 200_000L, 15_000L))
    }

    @Test
    fun `buffer prunes snapshots older than the 60s window but keeps one to serve it`() {
        val buf = DelayedValueBuffer<String, Int>()
        buf.record("k", 1, 0L)
        buf.record("k", 2, 100_000L)
        buf.record("k", 3, 200_000L)
        assertEquals("served at 60s", 3, buf.valueAt("k", 260_000L, 60_000L))
        assertTrue("old pruned", buf.snapshotCount("k") <= 2)
    }

    @Test
    fun `nextChangeAtMs is when a pending snapshot becomes due`() {
        val buf = DelayedValueBuffer<String, Int>()
        buf.record("k", 1, 0L)
        buf.record("k", 2, 10_000L)
        assertEquals("due at 10s+30s", 40_000L, buf.nextChangeAtMs(35_000L, 30_000L))
        assertEquals("nothing pending", null, buf.nextChangeAtMs(50_000L, 30_000L))
        assertEquals("delay 0 nothing pending", null, buf.nextChangeAtMs(20_000L, 0L))
    }

    @Test
    fun `old local json without the keys decodes to defaults`() {
        val p = Json { ignoreUnknownKeys = true }.decodeFromString<RadarPrefs>("""{"opt_in_state":"accepted"}""")
        assertTrue("shown default", p.scoresShown)
        assertEquals("delay default", 0, p.scoreDelayS)
    }

    @Test
    fun `sync payload contains both keys`() {
        val o = RadarPrefs(scoresShown = false, scoreDelayS = 30).toSyncJson()
        assertFalse(o["scores_shown"]!!.jsonPrimitive.boolean)
        assertEquals(30, o["score_delay_s"]!!.jsonPrimitive.int)
    }
}
