package com.nuvio.tv.core.telemetry.playback

import com.nuvio.tv.core.telemetry.FlightRecorder
import com.nuvio.tv.core.telemetry.OncePerVersionStore
import com.nuvio.tv.core.telemetry.SessionHealth
import com.nuvio.tv.core.telemetry.TelemetryGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTelemetryRecorderTest {
    private var consent = true
    private var now = 0L
    private val health = SessionHealth()
    private val events = ArrayList<Pair<String, Map<String, Any>>>()
    private val qaLines = ArrayList<String>()
    private var memory: MemorySample? = null

    private fun recorder(qa: Boolean = false) = PlaybackTelemetryRecorder(
        consent = { consent },
        count = { health.increment(it) },
        sendEvent = { name, props -> events += name to props },
        qa = QaMirror(qa) { qaLines += it },
        lastMemory = { memory },
        nowMs = { now },
    )

    @Test
    fun `routine successes only increment session health counts and emit no event`() {
        val r = recorder()
        r.ttff(PlayKind.LIVE, PlayEngine.MEDIA3, 1_840)
        r.ttff(PlayKind.LIVE, PlayEngine.MEDIA3, 1_100)
        r.ttff(PlayKind.VOD, PlayEngine.LIBMPV, 9_000)
        r.teardown(PlayEngine.MEDIA3, 120, failed = false)
        r.multiviewOpened()
        r.stall(PlayKind.LIVE, StallOutcome.RECOVERED, 3_000)
        r.stall(PlayKind.LIVE, StallOutcome.ABANDONED, 20_000)
        r.fallback(PlayEngine.MEDIA3, PlayEngine.LIBMPV, FallbackTrigger.DECODER_FAILED)

        assertTrue("no event for routine signals", events.isEmpty())
        assertEquals(
            "counts",
            mapOf(
                "ttff:live:media3:lt2000" to 2,
                "ttff:vod:libmpv:ge8000" to 1,
                "teardown:media3:clean" to 1,
                "multiview:opened" to 1,
                "stall:live:recovered" to 1,
                "stall_ms:live:lt5000" to 1,
                "stall:live:abandoned" to 1,
                "stall_ms:live:lt45000" to 1,
                "fallback:media3:libmpv:decoder_failed" to 1,
            ),
            health.drain(),
        )
    }

    @Test
    fun `every count the recorder writes is a valid contract key`() {
        val r = recorder()
        PlayKind.entries.forEach { k -> PlayEngine.entries.forEach { e -> r.ttff(k, e, 777) } }
        PlayKind.entries.forEach { k -> StallOutcome.entries.forEach { o -> r.stall(k, o, 20_000) } }
        PlayEngine.entries.forEach { e -> r.teardown(e, 2_000, failed = false); r.teardown(e, 5, failed = true) }
        FallbackTrigger.entries.forEach { r.fallback(PlayEngine.MEDIA3, PlayEngine.LIBMPV, it) }
        r.multiviewOpened()
        r.multiviewTiles(3, 2, setOf(7))
        r.multiviewClosed()
        val drained = health.drain()
        assertTrue("something was counted", drained.isNotEmpty())
        val bad = drained.keys.mapNotNull { PlaybackTelemetryPrivacy.countKeyViolation(it) }
        assertEquals("invalid keys", emptyList<String>(), bad)
    }

    @Test
    fun `with the telemetry switch off nothing is counted and nothing is sent`() {
        consent = false
        val r = recorder()
        r.ttff(PlayKind.LIVE, PlayEngine.MEDIA3, 500)
        r.fallback(PlayEngine.MEDIA3, PlayEngine.LIBMPV, FallbackTrigger.USER)
        r.stall(PlayKind.PREVIEW, StallOutcome.GAVE_UP, 60_000)
        r.teardown(PlayEngine.LIBMPV, 9_000, failed = true)
        r.multiviewOpened()
        r.multiviewTiles(4, 3, setOf(1, 2))
        r.multiviewClosed()
        assertTrue("no counts", health.drain().isEmpty())
        assertTrue("no events", events.isEmpty())
    }

    @Test
    fun `switching off in the middle of a multiview session stops the summary too`() {
        val r = recorder()
        r.multiviewOpened()
        r.multiviewTiles(2, 2, emptySet())
        consent = false
        now = 400_000
        r.multiviewClosed()
        assertTrue("no event once switched off", events.isEmpty())
        assertFalse("no closed count", health.drain().containsKey("multiview:closed"))
    }

    @Test
    fun `a multiview session counts open and close and sends exactly one enum only event`() {
        memory = MemorySample(1L, 430, 90, 130, 280, 101, 220)
        val r = recorder()
        now = 1_000
        r.multiviewOpened()
        r.multiviewOpened() // a repeat while open is ignored
        r.multiviewTiles(2, 2, emptySet())
        r.multiviewTiles(4, 3, setOf(9))
        r.multiviewTiles(4, 3, setOf(9)) // the same failure is not counted twice
        r.memorySampled(MemorySample(2L, 455, 0, 0))
        now = 1_000 + 130_000
        r.multiviewClosed()
        r.multiviewClosed() // idempotent

        assertEquals(
            "counts",
            mapOf("multiview:opened" to 1, "multiview:tile_failed" to 1, "multiview:closed" to 1, "multiview_tiles_max:4" to 1),
            health.drain(),
        )
        assertEquals("one event", 1, events.size)
        val (name, props) = events.single()
        assertEquals("multiview_session", name)
        assertEquals(4, props["tiles_max"])
        assertEquals("lt600", props["duration_s_bucket"])
        assertEquals(1, props["tile_failures"])
        assertEquals(3, props["hw_decoders_in_use"])
        assertEquals("peak of the session, not just the last sample", 455, props["pss_mb_peak"])
        assertEquals("user", props["end_reason"])
        assertEquals("memory context rides along", 430, props["mem_pss_mb"])
        assertEquals(emptyList<String>(), PlaybackTelemetryPrivacy.violations(props))
    }

    @Test
    fun `the qa mirror writes exact values and ignores the switch while off by default`() {
        val off = recorder(qa = false)
        off.ttff(PlayKind.LIVE, PlayEngine.MEDIA3, 1_840)
        assertTrue("off by default", qaLines.isEmpty())
        health.drain()

        consent = false
        val on = recorder(qa = true)
        on.ttff(PlayKind.LIVE, PlayEngine.MEDIA3, 1_840)
        on.fallback(PlayEngine.MEDIA3, PlayEngine.LIBMPV, FallbackTrigger.TIMEOUT)
        on.stall(PlayKind.VOD, StallOutcome.RECOVERED, 2_500)
        on.teardown(PlayEngine.LIBMPV, 1_800, failed = false)
        on.memorySampled(MemorySample(1L, 400, 80, 100, 250, 90, 200))
        assertEquals(
            "exact lines",
            listOf(
                """{"ev":"ttff","kind":"live","engine":"media3","ms":1840}""",
                """{"ev":"fallback","from":"media3","to":"libmpv","trigger":"timeout"}""",
                """{"ev":"stall","kind":"vod","outcome":"recovered","ms":2500}""",
                """{"ev":"teardown","engine":"libmpv","outcome":"slow","ms":1800}""",
                """{"ev":"mem","mem_pss_mb":400,"mem_java_mb":80,"mem_native_mb":100,"mem_avail_mb":250,"mem_threads":90,"mem_fds":200}""",
            ),
            qaLines,
        )
        assertTrue("the mirror is not a network path: nothing counted while switched off", health.drain().isEmpty())
    }

    @Test
    fun `every qa line avoids the forbidden keys and values stay enums or numbers`() {
        val r = recorder(qa = true)
        PlayKind.entries.forEach { k -> PlayEngine.entries.forEach { e -> r.ttff(k, e, 1) } }
        r.multiviewOpened(); r.multiviewTiles(2, 1, setOf(1)); r.multiviewClosed()
        assertTrue(qaLines.isNotEmpty())
        val forbidden = PlaybackTelemetryPrivacy.FORBIDDEN_KEYS
        qaLines.forEach { line ->
            val keys = Regex("\"([a-z_0-9]+)\":").findAll(line).map { it.groupValues[1] }.toSet()
            assertTrue("no forbidden key in $line", keys.none { it in forbidden })
            assertFalse("no url in $line", line.contains("://"))
        }
    }

    @Test
    fun `the gate lets the multiview session through as a plain event with no trail`() {
        val gate = TelemetryGate(FlightRecorder(), health, object : OncePerVersionStore {
            override fun firstTime(key: String) = true
        }, "1.0.0", { emptyList() })
        val result = gate.process("multiview_session", mapOf("tiles_max" to 3, "end_reason" to "user"))
        assertTrue("sent", result is TelemetryGate.Result.Pass)
        val pass = result as TelemetryGate.Result.Pass
        assertFalse("not an error", pass.isError)
        assertFalse("no trail", pass.properties.containsKey("diag_trail"))
    }

    @Test
    fun `the process handle is a no-op until a recorder is installed`() {
        PlaybackTelemetry.recorder = null
        PlaybackTelemetry.ttff(PlayKind.LIVE, PlayEngine.MEDIA3, 10)
        PlaybackTelemetry.multiviewClosed()
        assertTrue("nothing counted", health.drain().isEmpty())
    }
}
