package com.nuvio.tv.playback.media3

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3BackendPrimitivesTest {
    @Test
    fun `selected video format emits only a valid factual frame rate`() {
        val facts = media3VideoFormatFacts(
            Format.Builder().setSampleMimeType("video/avc").setFrameRate(59.94f).build(),
        )

        assertEquals(
            listOf(
                Media3BackendEvent.VideoInputFormatChanged("video/avc"),
                Media3BackendEvent.VideoFrameRateChanged(59.94f),
            ),
            facts,
        )
    }

    @Test
    fun `unset and out of range Media3 frame rates stay unknown`() {
        listOf(C.RATE_UNSET, Float.NaN, Float.POSITIVE_INFINITY, 9f, 121f).forEach { frameRate ->
            assertNull(validMedia3VideoFrameRate(frameRate))
        }
        listOf(9f, 121f).forEach { frameRate ->
            assertNull(
                media3VideoFormatFacts(Format.Builder().setFrameRate(frameRate).build())
                    .filterIsInstance<Media3BackendEvent.VideoFrameRateChanged>()
                    .singleOrNull(),
            )
        }
        assertEquals(
            Media3BackendEvent.VideoFrameRateChanged(10f),
            media3VideoFormatFacts(Format.Builder().setFrameRate(10f).build()).last(),
        )
        assertEquals(
            Media3BackendEvent.VideoFrameRateChanged(120f),
            media3VideoFormatFacts(Format.Builder().setFrameRate(120f).build()).last(),
        )
    }

    @Test
    fun `timed out release is initiated once then awaited without facade reentry`() {
        var initiateCalls = 0
        var awaitCalls = 0
        val gate = Media3ReleaseProofGate(
            initiateRelease = { initiateCalls++; false },
            awaitRelease = { awaitCalls++; true },
        )

        assertFalse(gate.initiate())
        assertFalse(gate.initiate())
        assertTrue(gate.await())
        assertTrue(gate.await())
        assertEquals(1, initiateCalls)
        assertEquals(1, awaitCalls)
    }

    @Test
    fun `slow codec release can consume several await windows without a second initiation`() {
        var initiateCalls = 0
        var awaitCalls = 0
        val gate = Media3ReleaseProofGate(
            initiateRelease = { initiateCalls++; false },
            awaitRelease = { ++awaitCalls >= 3 },
        )

        assertFalse(gate.initiate())
        assertTrue(gate.awaitUpTo(maxAttempts = 4))
        assertTrue(gate.awaitUpTo(maxAttempts = 4))
        assertEquals(1, initiateCalls)
        assertEquals(3, awaitCalls)
    }

    @Test
    fun `unproven codec release remains fail closed after the bounded await budget`() {
        var initiateCalls = 0
        var awaitCalls = 0
        val gate = Media3ReleaseProofGate(
            initiateRelease = { initiateCalls++; false },
            awaitRelease = { awaitCalls++; false },
        )

        assertFalse(gate.initiate())
        assertFalse(gate.awaitUpTo(maxAttempts = 4))
        assertEquals(1, initiateCalls)
        assertEquals(4, awaitCalls)
    }

    @Test
    fun `raw live byte progress emits before transfer completion and is rate limited`() {
        var now = 1_000L
        var emissions = 0
        val signal = Media3ByteProgressSignal(clockNanos = { now }, minimumIntervalNanos = 500L)
        signal.bind { emissions++ }
        val source = mockk<DataSource>(relaxed = true)
        val spec = mockk<DataSpec>(relaxed = true)

        signal.onBytesTransferred(source, spec, true, 188)
        signal.onBytesTransferred(source, spec, true, 188)
        assertEquals(1, emissions)

        now += 500L
        signal.onBytesTransferred(source, spec, true, 188)
        assertEquals(2, emissions)

        signal.onBytesTransferred(source, spec, false, 188)
        signal.onBytesTransferred(source, spec, true, 0)
        signal.unbind()
        now += 500L
        signal.onBytesTransferred(source, spec, true, 188)
        assertEquals(2, emissions)
    }
}
