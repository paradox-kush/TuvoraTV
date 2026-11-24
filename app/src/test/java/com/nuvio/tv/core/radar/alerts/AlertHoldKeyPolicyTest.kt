package com.nuvio.tv.core.radar.alerts

import com.nuvio.tv.core.radar.alerts.AlertHoldKeyPolicy.Phase
import com.nuvio.tv.core.radar.alerts.AlertHoldKeyPolicy.Replay
import com.nuvio.tv.core.radar.alerts.AlertHoldKeyPolicy.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertHoldKeyPolicyTest {
    private val t = 10_000L
    private val none = State()

    private fun key(state: State, phase: Phase, at: Long, banner: Boolean = true, ok: Boolean = true) =
        AlertHoldKeyPolicy.onKey(state, banner, ok, phase, at)

    @Test
    fun `OK key codes`() {
        assertTrue("dpad center", AlertHoldKeyPolicy.isOkKey(23))
        assertTrue("enter", AlertHoldKeyPolicy.isOkKey(66))
        assertTrue("numpad enter", AlertHoldKeyPolicy.isOkKey(160))
        assertFalse("dpad down", AlertHoldKeyPolicy.isOkKey(20))
        assertFalse("back", AlertHoldKeyPolicy.isOkKey(4))
    }

    @Test
    fun `the down is consumed while a banner is visible`() {
        val down = key(none, Phase.Down, t)
        assertTrue("held back", down.consume)
        assertFalse("not a hold yet", down.fire)
        assertEquals("nothing replayed yet", Replay.None, down.replay)
        assertEquals("tracked", State(downAtMs = t), down.state)
    }

    @Test
    fun `a short press is replayed whole on the up`() {
        val down = key(none, Phase.Down, t)
        val up = key(down.state, Phase.Up, t + 120)
        assertEquals("down then up go back to the app", Replay.DownUp, up.replay)
        assertTrue("the original up is swallowed (the replay carries it)", up.consume)
        assertFalse("no action", up.fire)
        assertEquals("press over", none, up.state)
    }

    @Test
    fun `a hold fires once at the threshold and swallows the rest of the press`() {
        val down = key(none, Phase.Down, t)
        val early = key(down.state, Phase.Repeat, t + 499)
        assertFalse("not yet", early.fire)
        assertTrue("an early repeat is swallowed", early.consume)
        val at = key(early.state, Phase.Repeat, t + 500)
        assertTrue("fires at 500 ms", at.fire)
        assertTrue("and swallows", at.consume)
        assertEquals("nothing is replayed after an action", Replay.None, at.replay)
        val more = key(at.state, Phase.Repeat, t + 550)
        assertFalse("never fires twice", more.fire)
        assertTrue("later repeats swallowed", more.consume)
        val up = key(more.state, Phase.Up, t + 900)
        assertTrue("the up is swallowed", up.consume)
        assertEquals("no replay", Replay.None, up.replay)
        assertEquals("press over", none, up.state)
    }

    @Test
    fun `the timer fires a hold on remotes that send no repeats`() {
        val down = key(none, Phase.Down, t)
        assertFalse("too early", AlertHoldKeyPolicy.onTimer(down.state, true, t + 499).fire)
        val fired = AlertHoldKeyPolicy.onTimer(down.state, true, t + 500)
        assertTrue("fires", fired.fire)
        assertTrue("marks fired", fired.state.fired)
        assertFalse("only once", AlertHoldKeyPolicy.onTimer(fired.state, true, t + 700).fire)
        val up = key(fired.state, Phase.Up, t + 800)
        assertTrue("up swallowed after a timer fire", up.consume)
        assertEquals("not replayed", Replay.None, up.replay)
    }

    @Test
    fun `released before the threshold, a late timer does nothing`() {
        val down = key(none, Phase.Down, t)
        val up = key(down.state, Phase.Up, t + 200)
        val late = AlertHoldKeyPolicy.onTimer(up.state, true, t + 600)
        assertFalse("no fire", late.fire)
        assertEquals("no replay", Replay.None, late.replay)
    }

    @Test
    fun `the banner going away mid-press gives the down back`() {
        val down = key(none, Phase.Down, t)
        val gone = AlertHoldKeyPolicy.onBannerGone(down.state)
        assertEquals("down replayed", Replay.Down, gone.replay)
        assertEquals("press untracked afterwards", none, gone.state)
        assertFalse("no action", gone.fire)
        // The press then continues as the app's own: repeats and the up pass.
        assertFalse("a repeat passes", key(gone.state, Phase.Repeat, t + 600).consume)
        assertFalse("the up passes", key(gone.state, Phase.Up, t + 700).consume)
        assertEquals("timer finds the banner gone", Replay.Down, AlertHoldKeyPolicy.onTimer(down.state, false, t + 600).replay)
        assertEquals("a repeat finds the banner gone", Replay.Down, key(down.state, Phase.Repeat, t + 300, banner = false).replay)
    }

    @Test
    fun `a banner gone after the action fired changes nothing`() {
        val fired = AlertHoldKeyPolicy.onTimer(key(none, Phase.Down, t).state, true, t + 500)
        val gone = AlertHoldKeyPolicy.onBannerGone(fired.state)
        assertEquals("no replay", Replay.None, gone.replay)
        assertEquals("still swallowing the rest of the press", fired.state, gone.state)
    }

    @Test
    fun `no banner means nothing is tracked or touched`() {
        val down = key(none, Phase.Down, t, banner = false)
        assertEquals("not tracked", none, down.state)
        assertFalse("down passes", down.consume)
        val rep = key(down.state, Phase.Repeat, t + 600, banner = false)
        assertFalse("no fire", rep.fire)
        assertFalse("no swallow", rep.consume)
    }

    @Test
    fun `a press that began before the banner appeared is never touched`() {
        val rep = key(none, Phase.Repeat, t + 800, banner = true)
        assertFalse("no fire", rep.fire)
        assertFalse("no swallow", rep.consume)
        val up = key(none, Phase.Up, t + 900, banner = true)
        assertFalse("up passes", up.consume)
        assertEquals("nothing replayed", Replay.None, up.replay)
    }

    @Test
    fun `other keys always pass`() {
        val step = key(State(downAtMs = t), Phase.Repeat, t + 900, ok = false)
        assertFalse("dpad pass", step.consume)
        assertFalse("dpad no fire", step.fire)
        assertFalse("a down passes too", key(none, Phase.Down, t, ok = false).consume)
    }

    @Test
    fun `a lost up after the activity paused never fires the action`() {
        val down = key(none, Phase.Down, t)
        val paused = AlertHoldKeyPolicy.onPause(down.state)
        assertEquals("press forgotten", none, paused.state)
        assertFalse("no replay into a paused activity", paused.replay != Replay.None)
        val late = AlertHoldKeyPolicy.onTimer(paused.state, true, t + 600)
        assertFalse("the timer finds nothing to fire", late.fire)
        assertFalse("a repeat after resume is not ours", key(paused.state, Phase.Repeat, t + 700).fire)
    }
}
