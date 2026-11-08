package com.nuvio.tv.core.telemetry

import com.nuvio.tv.core.telemetry.TelemetrySendPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryPolicyTest {

    private fun decide(event: String, vararg props: Pair<String, Any?>) =
        TelemetrySendPolicy.decide(event, props.toMap())

    @Test
    fun routineSuccessesAreCountedNotSent() {
        assertEquals("loaded iptv load", Action.COUNT, decide("iptv_load", "outcome" to "loaded").action)
        assertEquals("ok epg resolve", Action.COUNT, decide("epg_resolve", "outcome" to "ok").action)
        assertEquals("empty epg is not an error", Action.COUNT, decide("epg_ingest", "outcome" to "empty").action)
        assertEquals("index build ok", Action.COUNT, decide("iptv_index_build", "outcome" to "ok").action)
        assertEquals("playback started", Action.COUNT, decide("playback_started").action)
    }

    @Test
    fun failuresAreSentWithTheTrail() {
        for (outcome in listOf("failed", "timeout")) {
            val d = decide("iptv_load", "outcome" to outcome)
            assertEquals("iptv_load $outcome", Action.SEND, d.action)
            assertTrue("trail attached for $outcome", d.withTrail)
        }
        assertEquals("epg ingest error", Action.SEND, decide("epg_ingest", "outcome" to "error").action)
    }

    @Test
    fun playbackDiagnosticsAreLoggedUnlessTheyCarryAFailure() {
        assertEquals("step", Action.RECORD, decide("clean_playback_diagnostic", "diagnostic_code" to "GRAPH_SELECTED").action)
        val failure = decide("clean_playback_diagnostic", "failure_code" to "NO_ELIGIBLE_GRAPH")
        assertEquals("failure sent", Action.SEND, failure.action)
        assertTrue("with trail", failure.withTrail)
        assertEquals("code kept", "NO_ELIGIBLE_GRAPH", failure.code)
    }

    @Test
    fun errorEventsAlwaysGoOutWithTheTrail() {
        for (e in listOf("live_playback_freeze", "app_crash_report", "user_issue_report", "app_exit")) {
            val d = decide(e)
            assertEquals(e, Action.SEND, d.action)
            assertTrue("$e trail", d.withTrail)
        }
    }

    @Test
    fun theExceptionPromotedFromACrashExitIsCountedNotSentAgain() {
        val promoted = decide("\$exception", "\$exception_values" to "[\"Android process exited abnormally: crash\"]")
        assertEquals("promoted copy counted", Action.COUNT, promoted.action)
        val real = decide("\$exception", "\$exception_values" to "[\"IllegalStateException\"]")
        assertEquals("a real exception is still sent", Action.SEND, real.action)
        assertTrue("with the trail", real.withTrail)
    }

    @Test
    fun codesAreNormalisedNotRejected() {
        assertEquals("dollar events keep their shape", "exception", DiagTrailPolicy.safeCode("\$exception"))
        assertEquals("route patterns", "player_id", DiagTrailPolicy.safeCode("player/{id}"))
        assertEquals("never empty", "unknown", DiagTrailPolicy.safeCode("///"))
    }

    @Test
    fun screensAreLoggedAndUnknownEventsAreNotLost() {
        assertEquals("screen", Action.RECORD, decide("\$screen", "\$screen_name" to "home").action)
        assertEquals("unknown event still sent", Action.SEND, decide("brand_new_event").action)
        assertEquals("audio profile once", Action.SEND_ONCE_PER_VERSION, decide("audio_output_profile").action)
    }

    @Test
    fun rateLimitAllowsOnePerInterval() {
        val limit = ReportRateLimit(minIntervalMs = 120_000)
        assertEquals("first allowed", 0L, limit.tryAcquire(1_000))
        assertEquals("second waits", 100_000L, limit.tryAcquire(21_000))
        assertEquals("after the interval", 0L, limit.tryAcquire(121_000))
    }

    @Test
    fun supportCodeFormat() {
        val code = SupportCodeGenerator.generate(java.util.Random(7))
        assertTrue(SupportCodeGenerator.isValid(code))
        assertFalse("rejects look-alikes", SupportCodeGenerator.isValid("0O1I2345"))
        assertEquals(9, SupportCodeGenerator.display(code).length)
    }
}
