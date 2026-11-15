package com.nuvio.tv.core.multiview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileFailurePolicyTest {
    private fun c(s: TileSignal) = TileFailurePolicy.classify(s)

    @Test
    fun `provider refusal statuses map to ProviderRefused`() {
        listOf(401, 403, 429, 456, 458, 509).forEach {
            assertEquals("HTTP $it", TileFailure.ProviderRefused, c(TileSignal.Http(it)))
        }
    }

    @Test
    fun `other http statuses are a network problem not a refusal`() {
        listOf(404, 410, 500, 502, 503).forEach {
            assertEquals("HTTP $it", TileFailure.Network, c(TileSignal.Http(it)))
        }
    }

    @Test
    fun `insufficient resource and reclaimed codec codes mean decoders ran out`() {
        assertEquals(TileFailure.DecoderUnavailable, c(TileSignal.Codec(1100)))
        assertEquals(TileFailure.DecoderUnavailable, c(TileSignal.Codec(1101)))
        assertEquals(TileFailure.DecoderUnavailable, c(TileSignal.Codec(TileFailurePolicy.MEDIA3_DECODER_INIT_FAILED)))
        assertEquals(TileFailure.DecoderUnavailable, c(TileSignal.Codec(TileFailurePolicy.MEDIA3_FORMAT_EXCEEDS_CAPABILITIES)))
        assertEquals("unknown codec code", TileFailure.DecoderUnavailable, c(TileSignal.Codec(-1)))
    }

    @Test
    fun `unsupported format codes map to Unsupported`() {
        assertEquals(TileFailure.Unsupported, c(TileSignal.Codec(TileFailurePolicy.MEDIA3_FORMAT_UNSUPPORTED)))
        assertEquals(TileFailure.Unsupported, c(TileSignal.UnsupportedFormat))
    }

    @Test
    fun `stall and open timeout mean no data`() {
        assertEquals(TileFailure.NoData, c(TileSignal.Stall(20_000)))
        assertEquals(TileFailure.NoData, c(TileSignal.OpenTimeout(20_000)))
    }

    @Test
    fun `network signal maps to Network`() {
        assertEquals(TileFailure.Network, c(TileSignal.Network))
    }

    @Test
    fun `unknown signal is the generic failure with plain text and retry`() {
        assertEquals(TileFailure.Other, c(TileSignal.Unknown))
        assertEquals("Something went wrong opening this stream.", TileFailure.Other.text)
        assertEquals(listOf(TileAction.RETRY, TileAction.REMOVE), TileFailure.Other.actions)
    }

    @Test
    fun `provider refused is never retried automatically but others may be`() {
        assertFalse(TileFailurePolicy.autoRetryAllowed(TileFailure.ProviderRefused))
        listOf(TileFailure.DecoderUnavailable, TileFailure.NoData, TileFailure.Unsupported, TileFailure.Network, TileFailure.Other)
            .forEach { assertTrue("$it", TileFailurePolicy.autoRetryAllowed(it)) }
    }

    @Test
    fun `text and actions follow the design table`() {
        assertEquals("The provider refused this stream, maybe a connection limit.", TileFailure.ProviderRefused.text)
        assertEquals(listOf(TileAction.RETRY), TileFailure.ProviderRefused.actions)
        assertEquals("This device ran out of video decoders for this one.", TileFailure.DecoderUnavailable.text)
        assertEquals(listOf(TileAction.RETRY, TileAction.REMOVE), TileFailure.DecoderUnavailable.actions)
        assertEquals("The stream stopped sending data.", TileFailure.NoData.text)
        assertEquals(listOf(TileAction.RETRY), TileFailure.NoData.actions)
        assertEquals("This device can't play this stream's format.", TileFailure.Unsupported.text)
        assertEquals(listOf(TileAction.REMOVE), TileFailure.Unsupported.actions)
        assertEquals("Couldn't reach the provider.", TileFailure.Network.text)
        assertEquals(listOf(TileAction.RETRY), TileFailure.Network.actions)
    }

    @Test
    fun `only decoder failures feed the ceiling ledger`() {
        assertTrue(TileFailurePolicy.countsTowardDecoderCeiling(TileFailure.DecoderUnavailable))
        TileFailure.entries.filter { it != TileFailure.DecoderUnavailable }
            .forEach { assertFalse("$it", TileFailurePolicy.countsTowardDecoderCeiling(it)) }
    }
}
