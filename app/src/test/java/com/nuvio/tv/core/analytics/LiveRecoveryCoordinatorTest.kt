package com.nuvio.tv.core.analytics

import com.nuvio.tv.core.analytics.LiveRecoveryCoordinator.Decision
import com.nuvio.tv.core.analytics.LiveRecoveryCoordinator.Engine
import com.nuvio.tv.core.analytics.LiveRecoveryCoordinator.Fault
import com.nuvio.tv.core.analytics.LiveRecoveryCoordinator.GiveUpReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the live-recovery ladder (design §4) and the one-connection budget (§3.6): the fault-family
 * branching, the transient-vs-persistent split, and the durable backward-PTS engine switch — all
 * without a player, provider, or device.
 */
class LiveRecoveryCoordinatorTest {

    /** A healthy first-incident on ExoPlayer: live, mpv available, nothing spent yet. */
    private fun input(
        fault: Fault = Fault.STALL,
        engine: Engine = Engine.EXO,
        isLiveFeed: Boolean = true,
        mpvAvailable: Boolean = true,
        engineSwitchedThisDwell: Boolean = false,
        connectionFreeAttempts: Int = 0,
        backwardJumpMs: Long? = null,
        reWedgesInWindow: Int = 0,
        reopensThisIncident: Int = 0,
        sinceLastReopenMs: Long = Long.MAX_VALUE,
        maxConnectionsOne: Boolean = false,
    ) = LiveRecoveryCoordinator.Input(
        fault = fault,
        engine = engine,
        isLiveFeed = isLiveFeed,
        mpvAvailable = mpvAvailable,
        engineSwitchedThisDwell = engineSwitchedThisDwell,
        connectionFreeAttempts = connectionFreeAttempts,
        backwardJumpMs = backwardJumpMs,
        reWedgesInWindow = reWedgesInWindow,
        reopensThisIncident = reopensThisIncident,
        sinceLastReopenMs = sinceLastReopenMs,
        maxConnectionsOne = maxConnectionsOne,
    )

    // --- Rung 1: provider limit & auth ---

    @Test
    fun `provider connection limit is terminal`() {
        assertEquals(
            "509 must never be retried",
            Decision.GiveUp(GiveUpReason.PROVIDER_LIMIT),
            LiveRecoveryCoordinator.evaluate(input(fault = Fault.PROVIDER_LIMIT)),
        )
    }

    @Test
    fun `auth failure mints a fresh link on the first attempt`() {
        assertEquals(
            "401/403/410/456 must mint a fresh single-use link, not reconnect to the dead one",
            Decision.FreshLinkMint,
            LiveRecoveryCoordinator.evaluate(input(fault = Fault.AUTH)),
        )
    }

    @Test
    fun `auth re-mint waits out the inter-reopen backoff`() {
        assertEquals(
            "a second re-open within 5s must wait so the provider slot can release",
            Decision.Wait,
            LiveRecoveryCoordinator.evaluate(
                input(fault = Fault.AUTH, reopensThisIncident = 0, sinceLastReopenMs = 2_000L),
            ),
        )
    }

    @Test
    fun `auth on a one-connection panel gives up after the single budgeted re-open`() {
        assertEquals(
            "max_connections=1 gets exactly one re-open, then a specific error",
            Decision.GiveUp(GiveUpReason.ATTEMPTS_EXHAUSTED),
            LiveRecoveryCoordinator.evaluate(
                input(fault = Fault.AUTH, maxConnectionsOne = true, reopensThisIncident = 1),
            ),
        )
    }

    // --- Rung 2: container mismatch ---

    @Test
    fun `container mismatch forces the other mime`() {
        assertEquals(
            "a .ts answered as HLS is fixed by forcing the other container, not a reconnect",
            Decision.ForceOtherContainer,
            LiveRecoveryCoordinator.evaluate(input(fault = Fault.CONTAINER_MISMATCH)),
        )
    }

    // --- Rung 3: the ~90% transient class ---

    @Test
    fun `a first stall tries a connection-free resync before spending a provider slot`() {
        assertEquals(
            "the cheap connection-free resync is the ~90% transient fix",
            Decision.ResyncConnectionFree,
            LiveRecoveryCoordinator.evaluate(input(fault = Fault.STALL, connectionFreeAttempts = 0)),
        )
    }

    @Test
    fun `after the cheap resyncs are spent a stall escalates to a budgeted reconnect`() {
        assertEquals(
            "once the free resyncs are used, a re-open is the next rung",
            Decision.Reconnect,
            LiveRecoveryCoordinator.evaluate(input(fault = Fault.STALL, connectionFreeAttempts = 2)),
        )
    }

    // --- Rung 4: the durable backward-PTS engine switch ---

    @Test
    fun `a large backward jump switches ExoPlayer to libmpv on the first occurrence`() {
        assertEquals(
            "the direct backward-PTS signal escalates straight to the clock-rebasing engine",
            Decision.EngineSwitch(Engine.MPV),
            LiveRecoveryCoordinator.evaluate(input(fault = Fault.STALL, backwardJumpMs = 110_182L)),
        )
    }

    @Test
    fun `the explicit BACKWARD_PTS fault switches engine`() {
        assertEquals(
            Decision.EngineSwitch(Engine.MPV),
            LiveRecoveryCoordinator.evaluate(input(fault = Fault.BACKWARD_PTS)),
        )
    }

    @Test
    fun `two re-wedges within the stable window are persistent and switch engine`() {
        assertEquals(
            "K=2 re-wedges of the same channel is the fallback persistence signal",
            Decision.EngineSwitch(Engine.MPV),
            LiveRecoveryCoordinator.evaluate(input(fault = Fault.STALL, reWedgesInWindow = 2)),
        )
    }

    @Test
    fun `a single re-wedge is still treated as transient`() {
        val d = LiveRecoveryCoordinator.evaluate(input(fault = Fault.STALL, reWedgesInWindow = 1))
        assertTrue("one re-wedge must not yet escalate to an engine switch: $d", d !is Decision.EngineSwitch)
    }

    @Test
    fun `the engine switch waits out the handoff backoff so the provider slot can release`() {
        assertEquals(
            "the switch is a re-open; honour the >=5s handoff backoff (review-C F3)",
            Decision.Wait,
            LiveRecoveryCoordinator.evaluate(input(fault = Fault.BACKWARD_PTS, sinceLastReopenMs = 3_000L)),
        )
    }

    @Test
    fun `the engine switch is guaranteed even on a one-connection panel over its reconnect cap`() {
        // The reconnect cap must NOT pre-empt the switch — it is the fix (review-C F3, reserved slot).
        assertEquals(
            Decision.EngineSwitch(Engine.MPV),
            LiveRecoveryCoordinator.evaluate(
                input(fault = Fault.BACKWARD_PTS, maxConnectionsOne = true, reopensThisIncident = 5),
            ),
        )
    }

    @Test
    fun `no ping-pong - a persistent wedge already on mpv gives a specific error`() {
        assertEquals(
            "if the robust engine also wedged, surface a specific error, never churn back to Exo",
            Decision.GiveUp(GiveUpReason.DECODE_UNSUPPORTED),
            LiveRecoveryCoordinator.evaluate(input(fault = Fault.BACKWARD_PTS, engine = Engine.MPV)),
        )
    }

    @Test
    fun `no second switch per dwell`() {
        assertEquals(
            Decision.GiveUp(GiveUpReason.DECODE_UNSUPPORTED),
            LiveRecoveryCoordinator.evaluate(input(fault = Fault.BACKWARD_PTS, engineSwitchedThisDwell = true)),
        )
    }

    @Test
    fun `a persistent wedge with no viable mpv on this device gives a specific error`() {
        assertEquals(
            "step-0 gate: if libmpv can't run here, be honest — never Exo-SW for a timestamp fault",
            Decision.GiveUp(GiveUpReason.DECODE_UNSUPPORTED),
            LiveRecoveryCoordinator.evaluate(input(fault = Fault.BACKWARD_PTS, mpvAvailable = false)),
        )
    }

    @Test
    fun `backward-PTS is live-scoped - a catch-up backward jump does not switch engine here`() {
        // review-C F6: the BACKWARD_PTS engine-switch rung is scoped to live (catch-up preserves
        // scrub position and is a separate lane), so a non-live jump falls through to a resync.
        val d = LiveRecoveryCoordinator.evaluate(
            input(fault = Fault.STALL, isLiveFeed = false, backwardJumpMs = 110_182L),
        )
        assertTrue("catch-up must not take the live engine-switch rung: $d", d !is Decision.EngineSwitch)
    }

    // --- Rung 5: budgeted reconnect ---

    @Test
    fun `a network drop reconnect waits out the backoff`() {
        assertEquals(
            Decision.Wait,
            LiveRecoveryCoordinator.evaluate(input(fault = Fault.NETWORK_DROP, sinceLastReopenMs = 1_000L)),
        )
    }

    @Test
    fun `a network drop reconnects when the backoff has elapsed`() {
        assertEquals(
            Decision.Reconnect,
            LiveRecoveryCoordinator.evaluate(input(fault = Fault.NETWORK_DROP, sinceLastReopenMs = 6_000L)),
        )
    }

    @Test
    fun `an unlimited panel keeps reconnecting up to the base attempt cap`() {
        assertEquals(
            "without a one-connection cap, the base MAX_ATTEMPTS governs",
            Decision.Reconnect,
            LiveRecoveryCoordinator.evaluate(input(fault = Fault.NETWORK_DROP, reopensThisIncident = 4)),
        )
        assertEquals(
            Decision.GiveUp(GiveUpReason.ATTEMPTS_EXHAUSTED),
            LiveRecoveryCoordinator.evaluate(
                input(fault = Fault.NETWORK_DROP, reopensThisIncident = LivePlaybackRecoveryPolicy.MAX_ATTEMPTS),
            ),
        )
    }
}
