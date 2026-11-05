package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.contracts.CatchUpReopen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions the player makes about a replay whose link is spent by being opened (a Stalker archive),
 * pulled out of the controller so they test without a player. JUnit order: (message, expected, actual).
 */
class SingleUseReplayPolicyTest {

    @Test
    fun `only a catch-up replay the port calls single-use ends with its message`() {
        assertEquals("a single-use replay ends with the port's words", "boom",
            SingleUseReplayPolicy.endMessage(isCatchUp = true, portMessage = "boom"))
        assertNull("an ordinary replay is left to the walk", SingleUseReplayPolicy.endMessage(true, null))
        assertNull("a non-replay never ends this way, whatever the port says", SingleUseReplayPolicy.endMessage(false, "boom"))
    }

    @Test
    fun `a single-use link is never probed, cached or fetched in parallel`() {
        assertFalse("AFR preflight and DoVi probe spend the link", SingleUseReplayPolicy.allowsOpeningProbes(singleUse = true))
        assertTrue(SingleUseReplayPolicy.allowsOpeningProbes(singleUse = false))
        assertFalse("parallel Range connections open the URL N times",
            SingleUseReplayPolicy.allowsParallelRange(requested = true, singleUse = true))
        assertTrue(SingleUseReplayPolicy.allowsParallelRange(requested = true, singleUse = false))
        assertFalse("a setting that is off stays off", SingleUseReplayPolicy.allowsParallelRange(requested = false, singleUse = false))
        assertFalse("the VOD cache re-reads ranges", SingleUseReplayPolicy.allowsVodCache(requested = true, singleUse = true))
        assertTrue(SingleUseReplayPolicy.allowsVodCache(requested = true, singleUse = false))
    }

    @Test
    fun `an error on a single-use replay ends with Retry before any walk or recovery`() {
        for (route in CatchUpReplayAdapter.ErrorRoute.entries) {
            assertEquals("single-use wins over $route", SingleUseReplayPolicy.ErrorRoute.END_WITH_RETRY,
                SingleUseReplayPolicy.routeError(singleUse = true, adapterRoute = route))
        }
        assertEquals(SingleUseReplayPolicy.ErrorRoute.DIALECT_WALK,
            SingleUseReplayPolicy.routeError(false, CatchUpReplayAdapter.ErrorRoute.DIALECT_WALK_FIRST))
        assertEquals(SingleUseReplayPolicy.ErrorRoute.PLAYER_RECOVERY,
            SingleUseReplayPolicy.routeError(false, CatchUpReplayAdapter.ErrorRoute.PLAYER_RECOVERY))
    }

    @Test
    fun `a startup engine failover never re-opens a single-use link`() {
        assertFalse(SingleUseReplayPolicy.allowsEngineFailover(singleUse = true))
        assertTrue(SingleUseReplayPolicy.allowsEngineFailover(singleUse = false))
    }

    @Test
    fun `retry opens the fresh link or ends with the portal's words`() {
        assertEquals(SingleUseReplayPolicy.RetryStep.Open("http://fresh"),
            SingleUseReplayPolicy.retryStep(CatchUpReopen.Link("http://fresh"), noAnswer = "silent"))
        assertEquals(SingleUseReplayPolicy.RetryStep.End("session cap"),
            SingleUseReplayPolicy.retryStep(CatchUpReopen.Refused("session cap"), noAnswer = "silent"))
        assertEquals("no answer is a terminal message, never a spinner", SingleUseReplayPolicy.RetryStep.End("silent"),
            SingleUseReplayPolicy.retryStep(null, noAnswer = "silent"))
    }

    @Test
    fun `the session is held from player start for a single-use replay and only once`() {
        assertTrue(SingleUseReplayPolicy.holdAtStart(singleUse = true, alreadyHeld = false))
        assertFalse("never twice", SingleUseReplayPolicy.holdAtStart(singleUse = true, alreadyHeld = true))
        assertFalse("an ordinary replay is held by its continuation check", SingleUseReplayPolicy.holdAtStart(false, false))
    }
}
