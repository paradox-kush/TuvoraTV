package com.nuvio.tv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/** B03 (D2) — when a Realtime channel that stopped being SUBSCRIBED must be torn down and rejoined. */
class RealtimeChannelHealthPolicyTest {

    private val grace = RealtimeChannelHealthPolicy.GRACE_MS

    @Test
    fun aSubscribedChannelStays() {
        assertEquals(RealtimeChannelHealthPolicy.Action.Stay, RealtimeChannelHealthPolicy.decide(subscribed = true, notSubscribedSinceMs = null, nowMs = 1_000_000))
    }

    @Test
    fun aBriefDropIsGivenTheGraceToRejoinByItself() {
        assertEquals(RealtimeChannelHealthPolicy.Action.Stay, RealtimeChannelHealthPolicy.decide(false, notSubscribedSinceMs = 1_000_000, nowMs = 1_000_000 + grace - 1))
    }

    @Test
    fun aChannelLeftUnsubscribedPastTheGraceIsResubscribed() {
        assertEquals(RealtimeChannelHealthPolicy.Action.Resubscribe, RealtimeChannelHealthPolicy.decide(false, notSubscribedSinceMs = 1_000_000, nowMs = 1_000_000 + grace))
    }
}
