package com.nuvio.tv.core.radar.alerts

import com.nuvio.tv.core.radar.alerts.AlertHoldKeyPolicy.Phase
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
    fun `a short press passes through untouched, down and up`() {
        val down = key(none, Phase.Down, t)
        assertFalse("down passes", down.consume)
        assertFalse("down does not fire", down.fire)
        val up = key(down.state, Phase.Up, t + 120)
        assertFalse("up passes", up.consume)
        assertFalse("up does not fire", up.fire)
        assertEquals("state reset", none, up.state)
    }

    @Test
    fun `a hold fires once at the threshold and swallows the rest of the press`() {
        val down = key(none, Phase.Down, t)
        val early = key(down.state, Phase.Repeat, t + 499)
        assertFalse("not yet", early.fire)
        assertTrue("an early repeat is swallowed so a held OK never repeats a click", early.consume)
        val at = key(early.state, Phase.Repeat, t + 500)
        assertTrue("fires at 500 ms", at.fire)
        assertTrue("and swallows", at.consume)
        val more = key(at.state, Phase.Repeat, t + 550)
        assertFalse("never fires twice", more.fire)
        assertTrue("later repeats swallowed", more.consume)
        val up = key(more.state, Phase.Up, t + 900)
        assertTrue("the up is swallowed so no click follows the action", up.consume)
        assertFalse("the up does not fire", up.fire)
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
        assertTrue("up swallowed after a timer fire", key(fired.state, Phase.Up, t + 800).consume)
    }

    @Test
    fun `released before the threshold, a late timer does nothing`() {
        val down = key(none, Phase.Down, t)
        val up = key(down.state, Phase.Up, t + 200)
        assertFalse("no fire after release", AlertHoldKeyPolicy.onTimer(up.state, true, t + 600).fire)
    }

    @Test
    fun `no banner means nothing is tracked or touched`() {
        val down = key(none, Phase.Down, t, banner = false)
        assertEquals("not tracked", none, down.state)
        val rep = key(down.state, Phase.Repeat, t + 600, banner = false)
        assertFalse("no fire", rep.fire)
        assertFalse("no swallow", rep.consume)
    }

    @Test
    fun `a press that began before the banner appeared is never touched`() {
        val rep = key(none, Phase.Repeat, t + 800, banner = true)
        assertFalse("no fire", rep.fire)
        assertFalse("no swallow", rep.consume)
    }

    @Test
    fun `the banner going away mid-hold does not fire`() {
        val down = key(none, Phase.Down, t)
        assertFalse("repeat without a banner", key(down.state, Phase.Repeat, t + 600, banner = false).fire)
        assertFalse("timer without a banner", AlertHoldKeyPolicy.onTimer(down.state, false, t + 600).fire)
    }

    @Test
    fun `other keys always pass`() {
        val step = key(State(downAtMs = t), Phase.Repeat, t + 900, ok = false)
        assertFalse("dpad pass", step.consume)
        assertFalse("dpad no fire", step.fire)
    }
}
