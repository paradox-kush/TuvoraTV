package com.nuvio.tv.core.player

import com.nuvio.tv.core.player.ForegroundStartPolicy.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// JUnit order: assertEquals(message, expected, actual).
class ForegroundStartPolicyTest {
    private val policy = ForegroundStartPolicy()

    private fun publish(): Long = assertNotNullId(policy.onPublish())

    private fun assertNotNullId(id: Long?): Long {
        assertNotNull("a start should be sent", id)
        return id!!
    }

    @Test
    fun stopWhileStartPendingIsDeferred() {
        val id = publish()
        assertFalse("a start that onStartCommand has not answered must never be stopped", policy.onHide())
        assertEquals("phase", Phase.StartPending(id, stopDeferred = true), policy.phase)
    }

    @Test
    fun promotionAfterADeferredStopStops() {
        val id = publish()
        policy.onHide()
        assertTrue("deferred stop runs on promotion", policy.onPromoted(id, stillWanted = false))
        assertEquals("phase", Phase.Idle, policy.phase)
    }

    @Test
    fun promotionWhileWantedRunsAndALaterStopIsImmediate() {
        val id = publish()
        assertFalse("still wanted", policy.onPromoted(id, stillWanted = true))
        assertEquals("phase", Phase.Running(id), policy.phase)
        assertTrue("running service may stop", policy.onHide())
    }

    @Test
    fun startWhileStartPendingOrRunningSendsNoSecondStart() {
        val id = publish()
        assertNull("one start in flight", policy.onPublish())
        policy.onPromoted(id, stillWanted = true)
        assertNull("running already", policy.onPublish())
    }

    @Test
    fun stopWhileIdleIsAllowed() {
        assertTrue("nothing pending, e.g. a sticky restart the tracker stops", policy.onHide())
    }

    @Test
    fun promoteFailureReturnsToIdle() {
        val id = publish()
        policy.onPromoteFailed(id)
        assertEquals("phase", Phase.Idle, policy.phase)
        assertNotNull("next start goes out", policy.onPublish())
    }

    @Test
    fun startThatThrewReturnsToIdle() {
        val id = publish()
        policy.onStartFailed(id)
        assertEquals("phase", Phase.Idle, policy.phase)
    }

    @Test
    fun staleReportsDoNotTouchANewerPendingStart() {
        val first = publish()
        policy.onPromoted(first, stillWanted = true)
        policy.onHide()
        val second = publish()
        assertFalse("stale promotion must not stop the newer start", policy.onPromoted(first, stillWanted = false))
        assertFalse("stale destroy changes nothing", policy.onServiceDestroyed(first, stillWanted = false))
        assertEquals("phase", Phase.StartPending(second), policy.phase)
    }

    @Test
    fun matchingDestroyReturnsToIdle() {
        val id = publish()
        policy.onPromoted(id, stillWanted = true)
        policy.onServiceDestroyed(id, stillWanted = false)
        assertEquals("phase", Phase.Idle, policy.phase)
    }
}
