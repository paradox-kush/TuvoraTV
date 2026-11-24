package com.nuvio.tv.core.network

import com.nuvio.tv.core.lifecycle.Disposable
import com.nuvio.tv.core.network.PlaybackNetworkSwitchPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Hand-port of NuvioMobile's PlaybackNetworkSwitchTest (JUnit: message, expected, actual). */
class PlaybackNetworkSwitchTest {

    // ── Policy ──────────────────────────────────────────────────────────────

    private fun decide(
        last: String?,
        current: String?,
        active: Boolean = true,
        singleUse: Boolean = false,
        lastReconnectAtMs: Long? = null,
        nowMs: Long = 100_000L,
    ) = PlaybackNetworkSwitchPolicy.decide(last, current, active, singleUse, lastReconnectAtMs, nowMs)

    @Test
    fun wifiToOtherNetworkMidPlaybackReconnectsTheStream() {
        assertEquals(Action.RECONNECT, decide(last = "wifi", current = "eth"))
    }

    @Test
    fun firstReportIsTheBaselineNotAChange() {
        assertEquals(Action.NONE, decide(last = null, current = "wifi"))
    }

    @Test
    fun sameNetworkAgainIsNoChange() {
        assertEquals(Action.NONE, decide(last = "wifi", current = "wifi"))
    }

    @Test
    fun goingOfflineHasNothingToMoveTo() {
        assertEquals(Action.NONE, decide(last = "wifi", current = null))
    }

    @Test
    fun singleUseLinkIsNeverReopenedOnlyIdleConnectionsAreReleased() {
        assertEquals(Action.RELEASE_IDLE, decide(last = "wifi", current = "eth", singleUse = true))
    }

    @Test
    fun noPlaybackOnlyReleasesIdleConnections() {
        assertEquals(Action.RELEASE_IDLE, decide(last = "wifi", current = "eth", active = false))
    }

    @Test
    fun aFlappingNetworkReconnectsAtMostOncePerInterval() {
        val now = 100_000L
        val recent = now - PlaybackNetworkSwitchPolicy.MIN_RECONNECT_INTERVAL_MS + 1
        val old = now - PlaybackNetworkSwitchPolicy.MIN_RECONNECT_INTERVAL_MS
        assertEquals(Action.RELEASE_IDLE, decide("eth", "wifi", lastReconnectAtMs = recent, nowMs = now))
        assertEquals(Action.RECONNECT, decide("eth", "wifi", lastReconnectAtMs = old, nowMs = now))
    }

    // ── Coordinator ─────────────────────────────────────────────────────────

    private class FakeMonitor : DefaultNetworkMonitor {
        var listener: ((String?) -> Unit)? = null
        var starts = 0
        var stops = 0
        override fun start(onDefaultNetwork: (String?) -> Unit): Disposable {
            starts++
            listener = onDefaultNetwork
            return Disposable { stops++; listener = null }
        }
        fun report(network: String?) = listener?.invoke(network)
    }

    private class FakePool : PlaybackConnectionPool {
        var idleReleases = 0
        var inFlightCancels = 0
        override fun releaseIdle() { idleReleases++ }
        override fun cancelInFlight() { inFlightCancels++ }
    }

    private class Fixture {
        val monitor = FakeMonitor()
        val pool = FakePool()
        var now = 0L
        val coordinator = PlaybackNetworkSwitchCoordinator(monitor, pool) { now }
    }

    @Test
    fun aPlayingStreamIsMovedToTheNewNetworkAfterTheSwitch() {
        val f = Fixture()
        var reconnects = 0
        f.coordinator.register(singleUseLink = false) { reconnects++ }
        f.monitor.report("wifi")
        f.monitor.report("eth")
        assertEquals("the playing stream must re-open on the new default network", 1, reconnects)
        assertEquals(1, f.pool.inFlightCancels)
        assertEquals(1, f.pool.idleReleases)
    }

    @Test
    fun wifiLostThenAnotherNetworkStillCountsAsASwitch() {
        val f = Fixture()
        var reconnects = 0
        f.coordinator.register(singleUseLink = false) { reconnects++ }
        f.monitor.report("wifi")
        f.monitor.report(null)
        f.monitor.report("eth")
        assertEquals(1, reconnects)
    }

    @Test
    fun aSingleUseStreamIsLeftAloneButIdleConnectionsAreDropped() {
        val f = Fixture()
        var reconnects = 0
        f.coordinator.register(singleUseLink = true) { reconnects++ }
        f.monitor.report("wifi")
        f.monitor.report("eth")
        assertEquals(0, reconnects)
        assertEquals("cancelling the read would spend a single-use link", 0, f.pool.inFlightCancels)
        assertEquals(1, f.pool.idleReleases)
    }

    @Test
    fun listensOnlyWhileAPlayerIsRegistered() {
        val f = Fixture()
        assertEquals(0, f.monitor.starts)
        val a = f.coordinator.register(singleUseLink = false) {}
        val b = f.coordinator.register(singleUseLink = false) {}
        assertEquals("one listener per process, however many players", 1, f.monitor.starts)
        a.dispose()
        assertEquals(0, f.monitor.stops)
        b.dispose()
        assertEquals(1, f.monitor.stops)
        assertNull(f.monitor.listener)
    }

    @Test
    fun aNewListeningPeriodStartsFromAFreshBaseline() {
        val f = Fixture()
        var reconnects = 0
        f.coordinator.register(singleUseLink = false) {}.also { f.monitor.report("wifi") }.dispose()
        f.coordinator.register(singleUseLink = false) { reconnects++ }
        f.monitor.report("eth")
        assertEquals("the first report after re-registering is the baseline, not a switch", 0, reconnects)
    }

    @Test
    fun oneFailingPlayerDoesNotStopTheOthersMoving() {
        val f = Fixture()
        var reconnects = 0
        f.coordinator.register(singleUseLink = false) { error("player already released") }
        f.coordinator.register(singleUseLink = false) { reconnects++ }
        f.monitor.report("wifi")
        f.monitor.report("eth")
        assertEquals(1, reconnects)
    }

    @Test
    fun aFlappingNetworkDoesNotLoopReloads() {
        val f = Fixture()
        var reconnects = 0
        f.coordinator.register(singleUseLink = false) { reconnects++ }
        f.monitor.report("wifi")
        f.now = 1_000L
        f.monitor.report("eth")
        f.now = 2_000L
        f.monitor.report("wifi")
        assertEquals(1, reconnects)
        f.now = 2_000L + PlaybackNetworkSwitchPolicy.MIN_RECONNECT_INTERVAL_MS
        f.monitor.report("eth")
        assertEquals(2, reconnects)
    }
}
