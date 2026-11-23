package com.nuvio.tv.core.radar

import org.junit.Assert.assertEquals
import org.junit.Test

class RadarPrefsMergeTest {
    private val local = RadarPrefs(scoresShown = false, scoreDelayS = 30)

    private fun merge(shown: Boolean?, delay: Int?, pending: Boolean) =
        RadarPrefsMerge.merge(local, "ev", RadarOptIn.ACCEPTED, true, shown, delay, pending)

    @Test
    fun `a server row without the columns keeps the local setting`() {
        val r = merge(null, null, false)
        assertEquals("shown kept", false, r.scoresShown)
        assertEquals("delay kept", 30, r.scoreDelayS)
        assertEquals("other fields still come from the server", "ev", r.featuredEventId)
        assertEquals("opt-in from server", RadarOptIn.ACCEPTED, r.optInState)
    }

    @Test
    fun `server values apply when present and nothing is pending`() {
        val r = merge(true, 15, false)
        assertEquals("shown", true, r.scoresShown)
        assertEquals("delay", 15, r.scoreDelayS)
    }

    @Test
    fun `a pending local toggle beats the pulled values`() {
        val r = merge(true, 0, true)
        assertEquals("local shown kept", false, r.scoresShown)
        assertEquals("local delay kept", 30, r.scoreDelayS)
    }

    @Test
    fun `a partial row only fills the present column`() {
        val r = merge(true, null, false)
        assertEquals("shown from server", true, r.scoresShown)
        assertEquals("delay local", 30, r.scoreDelayS)
    }

    @Test
    fun `an invalid server delay reads as off`() {
        assertEquals("sanitised", 0, merge(null, 45, false).scoreDelayS)
    }

    @Test
    fun `push tracker is pending after a change until that change is pushed`() {
        val t = SpoilerPushTracker()
        assertEquals("clean", false, t.pending)
        t.noteChange()
        assertEquals("dirty", true, t.pending)
        val token = t.beginPush()
        t.noteChange()                       // toggled again while the push was in flight
        t.pushSucceeded(token)
        assertEquals("newer change still pending", true, t.pending)
        t.pushSucceeded(t.beginPush())
        assertEquals("clean after the newest push", false, t.pending)
    }
}
