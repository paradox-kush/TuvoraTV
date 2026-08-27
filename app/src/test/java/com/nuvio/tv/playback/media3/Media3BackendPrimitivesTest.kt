package com.nuvio.tv.playback.media3

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3BackendPrimitivesTest {
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
