package com.nuvio.tv.core.telemetry.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** Bucket edges are exclusive upper bounds; a value on an edge belongs to the NEXT bucket. */
class PlaybackTelemetryBucketsTest {

    @Test
    fun `ttff boundaries`() {
        val cases = listOf(
            0L to "lt250", 249L to "lt250", 250L to "lt500", 499L to "lt500", 500L to "lt1000", 999L to "lt1000",
            1_000L to "lt2000", 1_999L to "lt2000", 2_000L to "lt4000", 3_999L to "lt4000",
            4_000L to "lt8000", 7_999L to "lt8000", 8_000L to "ge8000", 120_000L to "ge8000",
        )
        cases.forEach { (ms, bucket) -> assertEquals("ttff $ms ms", bucket, PlaybackTelemetryBuckets.ttff(ms)) }
    }

    @Test
    fun `stall boundaries`() {
        val cases = listOf(
            0L to "lt2000", 1_999L to "lt2000", 2_000L to "lt5000", 4_999L to "lt5000", 5_000L to "lt15000",
            14_999L to "lt15000", 15_000L to "lt45000", 44_999L to "lt45000", 45_000L to "ge45000",
        )
        cases.forEach { (ms, bucket) -> assertEquals("stall $ms ms", bucket, PlaybackTelemetryBuckets.stall(ms)) }
    }

    @Test
    fun `multiview duration boundaries`() {
        val cases = listOf(0L to "lt30", 29L to "lt30", 30L to "lt120", 119L to "lt120", 120L to "lt600", 599L to "lt600", 600L to "ge600")
        cases.forEach { (s, bucket) -> assertEquals("duration $s s", bucket, PlaybackTelemetryBuckets.durationSeconds(s)) }
    }

    @Test
    fun `ram class boundaries use the system reported total`() {
        val cases = listOf(
            900 to "lt1_2", 1_228 to "lt1_2", 1_229 to "lt2", 1_900 to "lt2", 2_047 to "lt2",
            2_048 to "lt3", 2_800 to "lt3", 3_071 to "lt3", 3_072 to "ge3", 7_600 to "ge3",
        )
        cases.forEach { (mb, cls) -> assertEquals("ram $mb MB", cls, PlaybackTelemetryBuckets.ramClass(mb)) }
    }

    @Test
    fun `http class covers every status family`() {
        val cases = listOf<Pair<Int?, String>>(
            null to "none", 200 to "2xx", 299 to "2xx", 301 to "3xx", 401 to "401_403", 403 to "401_403",
            404 to "404", 456 to "456", 400 to "4xx_other", 410 to "4xx_other", 458 to "4xx_other", 429 to "4xx_other",
            500 to "5xx", 599 to "5xx", 100 to "none", 600 to "none",
        )
        cases.forEach { (status, cls) -> assertEquals("http $status", cls, PlaybackTelemetryBuckets.httpClass(status)) }
    }

    @Test
    fun `teardown is slow only above 1500 ms and failed wins`() {
        assertEquals("1500 is still clean", TeardownOutcome.CLEAN, PlaybackTelemetryBuckets.teardownOutcome(1_500, false))
        assertEquals("1501 is slow", TeardownOutcome.SLOW, PlaybackTelemetryBuckets.teardownOutcome(1_501, false))
        assertEquals("failed wins over fast", TeardownOutcome.FAILED, PlaybackTelemetryBuckets.teardownOutcome(10, true))
    }

    @Test
    fun `resolution class uses the shorter side and unknown without a size`() {
        assertEquals("sd", "sd", PlaybackTelemetryBuckets.resClass(720, 576))
        assertEquals("hd720", "hd720", PlaybackTelemetryBuckets.resClass(1280, 720))
        assertEquals("fhd", "fhd", PlaybackTelemetryBuckets.resClass(1920, 1080))
        assertEquals("uhd", "uhd", PlaybackTelemetryBuckets.resClass(3840, 2160))
        assertEquals("missing size", "unknown", PlaybackTelemetryBuckets.resClass(null, 1080))
    }
}
