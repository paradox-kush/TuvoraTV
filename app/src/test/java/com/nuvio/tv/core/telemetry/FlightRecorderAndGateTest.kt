package com.nuvio.tv.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class FlightRecorderAndGateTest {

    private class Once : OncePerVersionStore {
        val seen = HashSet<String>()
        override fun firstTime(key: String) = seen.add(key)
    }

    private fun gate(recorder: FlightRecorder = FlightRecorder(), health: SessionHealth = SessionHealth(), prev: List<DiagEntry> = emptyList()) =
        TelemetryGate(recorder, health, Once(), "1.18.1", { prev })

    @Test
    fun ringKeepsOnlyTheNewestEntries() {
        val r = FlightRecorder(capacity = 5)
        repeat(12) { r.record(DiagArea.PLAYBACK, "step", mapOf("count" to it)) }
        val snap = r.snapshot()
        assertEquals(5, snap.size)
        assertEquals("oldest dropped", 7, snap.first().fields["count"])
    }

    @Test
    fun ringSurvivesConcurrentWriters() {
        val r = FlightRecorder(capacity = 50)
        val start = CountDownLatch(1)
        val threads = (1..8).map { t -> thread { start.await(); repeat(500) { r.record(DiagArea.UI, "w$t") } } }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(50, r.snapshot().size)
    }

    @Test
    fun crashSnapshotDoesNotBlockWhenTheLockIsHeld() {
        val r = FlightRecorder()
        r.record(DiagArea.UI, "x")
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val field = FlightRecorder::class.java.getDeclaredField("lock").apply { isAccessible = true }
        val lock = field.get(r) as java.util.concurrent.locks.ReentrantLock
        val t = thread { lock.lock(); holding.countDown(); release.await(); lock.unlock() }
        holding.await()
        val start = System.nanoTime()
        val snap = r.snapshotOrEmpty(10, timeoutMs = 30)
        val tookMs = (System.nanoTime() - start) / 1_000_000
        release.countDown(); t.join()
        assertTrue("returned empty instead of hanging", snap.isEmpty())
        assertTrue("bounded wait, took $tookMs ms", tookMs < 1000)
    }

    @Test
    fun sanitizeDropsUrlsHostsTextAndUnknownFields() {
        val out = DiagTrailPolicy.sanitize(
            mapOf(
                "engine" to "MEDIA3", "heap_used_mb" to 51, "failure_code" to "https://host.example/x?token=1",
                "reason" to "example.com", "kind" to "a title with spaces", "stream_url" to "x", "network" to "wifi",
            ),
        )
        assertEquals(mapOf("engine" to "MEDIA3", "heap_used_mb" to 51, "network" to "wifi"), out)
    }

    @Test
    fun trailIsBoundedAndKeepsTheNewestLines() {
        val entries = (1..2000).map { DiagEntry(it * 10L, DiagArea.PLAYBACK, "step_$it", mapOf("count" to it)) }
        val trail = DiagTrailPolicy.render(entries, maxEntries = 2000)
        assertTrue("under the byte budget", trail.length <= DiagTrailPolicy.MAX_TRAIL_BYTES)
        assertTrue("newest line kept", trail.lines().last().contains("step_2000"))
        assertFalse("oldest dropped", trail.contains("step_1 "))
    }

    @Test
    fun trailRoundTripsThroughJson() {
        val entries = listOf(DiagEntry(5, DiagArea.EPG, "ingest", mapOf("outcome" to "ok", "item_count" to 12)))
        assertEquals(entries, DiagTrailPolicy.fromJson(DiagTrailPolicy.toJson(entries)))
    }

    @Test
    fun gateDropsSuccessesAndCountsThem() {
        val health = SessionHealth()
        val g = gate(health = health)
        assertTrue(g.process("iptv_load", mapOf("outcome" to "loaded", "surface" to "hub_row")) is TelemetryGate.Result.Drop)
        assertTrue(g.process("playback_started", mapOf("kind" to "live")) is TelemetryGate.Result.Drop)
        assertEquals(mapOf("iptv_load:loaded:hub_row" to 1, "playback_started:live" to 1), health.drain())
    }

    @Test
    fun gateAttachesTheLeadUpToAnError() {
        val rec = FlightRecorder()
        val g = gate(recorder = rec)
        g.process("clean_playback_diagnostic", mapOf("diagnostic_code" to "GRAPH_SELECTED", "engine" to "MEDIA3"))
        val result = g.process("clean_playback_diagnostic", mapOf("failure_code" to "NO_PROGRESS", "engine" to "MEDIA3"))
        val pass = result as TelemetryGate.Result.Pass
        val trail = pass.properties["diag_trail"] as String
        assertTrue("step before the error is in the trail", trail.contains("GRAPH_SELECTED"))
        assertTrue("the error is the last line", trail.lines().last().contains("NO_PROGRESS"))
        assertTrue(pass.isError)
    }

    @Test
    fun gateKeepsATrailThatAlreadyCameWithTheEvent() {
        val result = gate().process("app_crash_report", mapOf("diag_trail" to "from the crash")) as TelemetryGate.Result.Pass
        assertEquals("from the crash", result.properties["diag_trail"])
    }

    @Test
    fun exitReportsUseThePreviousRunsTrail() {
        val prev = listOf(DiagEntry(1, DiagArea.PLAYBACK, "last_run_step"))
        val result = gate(prev = prev).process("app_exit", mapOf("reason" to "native_crash")) as TelemetryGate.Result.Pass
        assertTrue((result.properties["diag_trail"] as String).contains("last_run_step"))
    }

    private fun stuck(instance: String) = mapOf(
        "diagnostic_code" to "LIVE_PICTURE_STUCK",
        "engine" to "LIBMPV",
        "freeze_kind" to "VIDEO_ONLY",
        "ladder_rung" to "KEEP_PLAYING",
        "session_instance" to instance,
    )

    @Test
    fun aStuckPictureWithLiveAudioIsSentOncePerSessionInstanceWithItsTrail() {
        val g = gate()
        val first = g.process("clean_playback_diagnostic", stuck("ab12"))
        assertTrue("sent, not just recorded", first is TelemetryGate.Result.Pass)
        assertTrue((first as TelemetryGate.Result.Pass).properties.containsKey("diag_trail"))
        assertTrue("the same session reports once", g.process("clean_playback_diagnostic", stuck("ab12")) is TelemetryGate.Result.Drop)
        assertTrue("another session is its own report", g.process("clean_playback_diagnostic", stuck("cd34")) is TelemetryGate.Result.Pass)
    }

    @Test
    fun audioProfileIsSentOncePerVersion() {
        val g = gate()
        assertTrue(g.process("audio_output_profile", emptyMap()) is TelemetryGate.Result.Pass)
        assertNull((g.process("audio_output_profile", emptyMap()) as? TelemetryGate.Result.Pass))
    }

    @Test
    fun noEligibleGraphReasonsAndStreamFactsReachTheOnDeviceTrail() {
        val recorder = FlightRecorder()
        val formatted = com.nuvio.tv.playback.wiring.PlaybackDiagnosticFormatter.format(
            com.nuvio.tv.playback.core.PlaybackDiagnosticEvent(
                generation = 4,
                code = com.nuvio.tv.playback.core.PlaybackDiagnosticCode.ENGINE_OPERATION_FAILED,
                failure = com.nuvio.tv.playback.core.PlaybackFailure(
                    code = com.nuvio.tv.playback.core.FailureCode.NO_ELIGIBLE_GRAPH,
                    domain = com.nuvio.tv.playback.core.FailureDomain.UNKNOWN,
                    phase = com.nuvio.tv.playback.core.FailurePhase.GRAPH_SELECTION,
                    retryability = com.nuvio.tv.playback.core.Retryability.FATAL,
                    deterministic = true,
                    graphRejection = com.nuvio.tv.playback.core.GraphRejection(
                        rejectedBy = com.nuvio.tv.playback.core.GraphRejectionReason.AUDIO_OUTPUT_PREFERENCE,
                        candidateGraphs = 2,
                        removedBy = mapOf(com.nuvio.tv.playback.core.GraphRejectionReason.AUDIO_OUTPUT_PREFERENCE to 2),
                        stream = com.nuvio.tv.playback.core.StreamFactCodes(
                            codec = com.nuvio.tv.playback.core.StreamCodecCode.H264,
                            container = com.nuvio.tv.playback.core.StreamContainerCode.HLS,
                            height = com.nuvio.tv.playback.core.StreamHeightCode.FHD,
                            hdr = com.nuvio.tv.playback.core.StreamHdrCode.DV,
                        ),
                    ),
                ),
            ),
        )
        gate(recorder = recorder).process(formatted.eventName, formatted.properties)

        val fields = recorder.snapshot().last().fields
        assertEquals("AUDIO_OUTPUT_PREFERENCE", fields["rejected_by"])
        assertEquals(2, fields["candidate_graphs"])
        assertEquals("h264", fields["stream_codec"])
        assertEquals("hls", fields["stream_container"])
        assertEquals("fhd", fields["stream_height"])
        assertEquals("dv", fields["stream_hdr"])
        assertEquals("NO_ELIGIBLE_GRAPH", fields["failure_code"])
    }
}
