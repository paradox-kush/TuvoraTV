package com.nuvio.tv.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `first_run_step` is an EXPLICIT send: not the unknown-event default, not counted into session_health, no trail. */
class FirstRunSendPolicyTest {
    private val props = mapOf<String, Any?>(
        "step" to "source_added", "schema_version" to 1, "launch_index" to 1, "secs_since_first_launch" to 7,
        "source_kind" to "m3u", "via" to "manual",
    )

    @Test
    fun theEventIsListedAsAnExplicitSend() {
        assertTrue("explicitly listed, so it does not ride on the unknown-event default", FirstRunFunnel.EVENT in TelemetrySendPolicy.explicitSendEvents)
        val d = TelemetrySendPolicy.decide("first_run_step", props)
        assertEquals("action", TelemetrySendPolicy.Action.SEND, d.action)
        assertFalse("not an error event: no trail", d.withTrail)
        assertNull("no once-key: the funnel's own once-gate decides", d.onceKey)
    }

    @Test
    fun firstLeaveStillSends() {
        val leave = mapOf<String, Any?>(
            "step" to "first_leave", "schema_version" to 1, "launch_index" to 1, "secs_since_first_launch" to 30,
            "last_screen" to "xtream_hub", "secs_in_app" to 25, "screens_seen" to 3,
            "opened_source_form" to true, "tried_save" to true, "save_error" to "unreachable",
        )
        val d = TelemetrySendPolicy.decide("first_run_step", leave)
        assertEquals("action", TelemetrySendPolicy.Action.SEND, d.action)
        assertFalse("no trail", d.withTrail)
        assertNull("no once-key", d.onceKey)
    }

    @Test
    fun aFailureLookingPropertyDoesNotMakeItAnError() {
        // Even an `outcome` or `surface` value that other events use for failures must not turn this into a trail event.
        val d = TelemetrySendPolicy.decide("first_run_step", props + mapOf("surface" to "hub_categories", "outcome" to "failed"))
        assertEquals("still SEND", TelemetrySendPolicy.Action.SEND, d.action)
        assertFalse("still no trail", d.withTrail)
    }

    @Test
    fun theGatePassesItUnchangedWithNoTrailAndDoesNotCountIt() {
        val health = SessionHealth()
        val recorder = FlightRecorder()
        recorder.record(DiagArea.PLAYBACK, "step")
        val gate = TelemetryGate(recorder, health, object : OncePerVersionStore { override fun firstTime(key: String) = true }, "1.0", { emptyList() })
        val r = gate.process("first_run_step", props)
        val pass = r as? TelemetryGate.Result.Pass ?: error("expected Pass, got $r")
        assertFalse("not flagged as an error", pass.isError)
        assertFalse("no diag_trail attached", pass.properties.containsKey("diag_trail"))
        assertEquals("properties untouched", props, pass.properties)
        assertTrue("never counted into session_health", health.drain().isEmpty())
        // And it is repeatable through the gate: the funnel, not the gate, owns once-only.
        assertTrue("a second pass is also Pass", gate.process("first_run_step", props) is TelemetryGate.Result.Pass)
    }
}
