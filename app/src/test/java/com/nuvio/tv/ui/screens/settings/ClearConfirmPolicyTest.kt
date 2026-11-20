package com.nuvio.tv.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** F-8 / UX-35: TV "Clear play counts" must ask "Tap Clear again" like the phone, not clear on one press. */
class ClearConfirmPolicyTest {

    private val idle = ClearConfirmPolicy.State()

    @Test
    fun `first press arms and does not clear`() {
        val r = ClearConfirmPolicy.onPress(idle, nowMs = 1_000)
        assertEquals("first press only arms", ClearConfirmPolicy.Phase.Armed, r.state.phase)
        assertFalse("first press must not clear", r.clear)
    }

    @Test
    fun `second press after the double-fire guard clears`() {
        val armed = ClearConfirmPolicy.onPress(idle, nowMs = 1_000).state
        val r = ClearConfirmPolicy.onPress(armed, nowMs = 1_000 + ClearConfirmPolicy.DOUBLE_FIRE_GUARD_MS)
        assertEquals("second press confirms", true, r.clear)
        assertEquals("then shows Cleared", ClearConfirmPolicy.Phase.Cleared, r.state.phase)
    }

    @Test
    fun `a duplicate OK within the guard window neither confirms nor disarms`() {
        val armed = ClearConfirmPolicy.onPress(idle, nowMs = 1_000).state
        val r = ClearConfirmPolicy.onPress(armed, nowMs = 1_100)
        assertFalse("an OK repeat must not confirm", r.clear)
        assertEquals("stays armed", ClearConfirmPolicy.Phase.Armed, r.state.phase)
    }

    @Test
    fun `armed reverts on timeout and when focus leaves`() {
        val armed = ClearConfirmPolicy.onPress(idle, nowMs = 0).state
        assertEquals("timeout reverts", ClearConfirmPolicy.Phase.Idle, ClearConfirmPolicy.onTimeout(armed).phase)
        assertEquals("focus loss reverts", ClearConfirmPolicy.Phase.Idle, ClearConfirmPolicy.onFocusLost(armed).phase)
    }

    @Test
    fun `cleared reverts on timeout and a press after it arms again`() {
        val cleared = ClearConfirmPolicy.State(ClearConfirmPolicy.Phase.Cleared, 0)
        assertEquals("cleared fades", ClearConfirmPolicy.Phase.Idle, ClearConfirmPolicy.onTimeout(cleared).phase)
        val r = ClearConfirmPolicy.onPress(cleared, nowMs = 10_000)
        assertEquals("press after Cleared re-arms", ClearConfirmPolicy.Phase.Armed, r.state.phase)
        assertFalse("and does not clear", r.clear)
    }

    @Test
    fun `window matches the phone`() {
        assertEquals("phone CLEAR_CONFIRM_WINDOW_MS", 5_000L, ClearConfirmPolicy.CONFIRM_WINDOW_MS)
        assertEquals("phone CLEARED_SHOWN_MS", 3_000L, ClearConfirmPolicy.CLEARED_SHOWN_MS)
    }
}
