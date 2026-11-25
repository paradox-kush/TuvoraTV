package com.nuvio.tv.core.telemetry.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every payload and count key the contract can produce is built here and run through the validator. */
class PlaybackTelemetryPrivacyTest {

    private val buckets = listOf("lt250", "lt500", "lt1000", "lt2000", "lt4000", "lt8000", "ge8000")
    private val stallBuckets = listOf("lt2000", "lt5000", "lt15000", "lt45000", "ge45000")

    /** Every count key the contract can generate, from the closed enums. */
    private fun everyCountKey(): List<String> = buildList {
        for (k in PlayKind.entries) for (e in PlayEngine.entries) for (b in buckets) add(PlaybackTelemetryKeys.ttff(k, e, b))
        for (f in PlayEngine.entries) for (t in PlayEngine.entries) for (g in FallbackTrigger.entries) add(PlaybackTelemetryKeys.fallback(f, t, g))
        for (k in PlayKind.entries) for (o in StallOutcome.entries) add(PlaybackTelemetryKeys.stall(k, o))
        for (k in PlayKind.entries) for (b in stallBuckets) add(PlaybackTelemetryKeys.stallMs(k, b))
        for (e in PlayEngine.entries) for (o in TeardownOutcome.entries) add(PlaybackTelemetryKeys.teardown(e, o))
        for (m in MultiviewEvent.entries) add(PlaybackTelemetryKeys.multiview(m))
        for (n in 1..9) add(PlaybackTelemetryKeys.multiviewTilesMax(n))
    }

    @Test
    fun `every generated count key is valid`() {
        val bad = everyCountKey().mapNotNull { PlaybackTelemetryPrivacy.countKeyViolation(it) }
        assertEquals("count key violations", emptyList<String>(), bad)
    }

    @Test
    fun `a count key with a host a url or a free word is rejected`() {
        val bad = listOf(
            "ttff:live:media3:fast", "ttff:live:media3", "ttff:channel:media3:lt250", "stall:live:crashed",
            "fallback:media3:libmpv:http://x", "teardown:exo:clean", "multiview:opened:extra", "multiview_tiles_max:12",
            "multiview_tiles_max:0", "playback_started:live", "ttff:live:media3:lt250:provider.example.com",
        )
        bad.forEach { assertNotNull("must be rejected: $it", PlaybackTelemetryPrivacy.countKeyViolation(it)) }
    }

    @Test
    fun `forbidden names are caught including the redacted four and url like suffixes`() {
        listOf("code", "state", "url", "token", "host", "user", "password", "mac", "key", "stream_url", "api_token", "base_host")
            .forEach { assertTrue("$it is forbidden", PlaybackTelemetryPrivacy.isForbiddenKey(it)) }
        listOf("tiles_max", "mem_pss_mb", "http_class", "end_reason", "ram_class", "attempt")
            .forEach { assertFalse("$it is fine", PlaybackTelemetryPrivacy.isForbiddenKey(it)) }
    }

    @Test
    fun `validator rejects a string outside its enum and a string property outside the contract`() {
        assertEquals(1, PlaybackTelemetryPrivacy.violations(mapOf("end_reason" to "the user was sad")).size)
        assertEquals(1, PlaybackTelemetryPrivacy.violations(mapOf("source_type" to "provider.example.com")).size)
        assertEquals(1, PlaybackTelemetryPrivacy.violations(mapOf("channel_title" to "BBC One")).size)
        assertEquals(1, PlaybackTelemetryPrivacy.violations(mapOf("url" to 3)).size)
        assertTrue(PlaybackTelemetryPrivacy.violations(mapOf("tiles_max" to 4, "ok" to true)).isEmpty())
    }

    @Test
    fun `the multiview session payload passes for every end reason and bucket`() {
        for (reason in MultiviewEndReason.entries) for (seconds in listOf(0L, 45L, 300L, 4_000L)) {
            val summary = MultiviewSessionSummary(
                tilesMax = 4, durationSeconds = seconds, tileFailures = 1, hwDecodersInUse = 3, endReason = reason,
            )
            val sample = MemorySample(1L, 412, 88, 120, 301, 96, 210)
            val payload = summary.toProperties(pssMbPeak = 430) + sample.toProperties()
            assertEquals("payload $reason/$seconds", emptyList<String>(), PlaybackTelemetryPrivacy.violations(payload))
            assertEquals(
                "exact keys",
                setOf(
                    "schema_version", "tiles_max", "duration_s_bucket", "tile_failures", "hw_decoders_in_use", "pss_mb_peak",
                    "end_reason", "mem_pss_mb", "mem_java_mb", "mem_native_mb", "mem_avail_mb", "mem_threads", "mem_fds",
                ),
                payload.keys,
            )
        }
    }

    @Test
    fun `memory and previous run properties pass the validator`() {
        val exit = MemoryContext.forExit("412,88,120,301,96,210", null, null, null, null) +
            MemoryContext.previousRun(wasPlaying = true, kind = "live", surface = "fullscreen", engine = "libmpv", tiles = 0)
        assertEquals(emptyList<String>(), PlaybackTelemetryPrivacy.violations(exit))
        val multiview = MemoryContext.previousRun(wasPlaying = false, kind = null, surface = null, engine = null, tiles = 4)
        assertEquals(emptyList<String>(), PlaybackTelemetryPrivacy.violations(multiview))
        assertNull("no mem value is invented for a run that never sampled", MemoryContext.forExit(null, null, null, null, null)["mem_avail_mb"])
    }
}
