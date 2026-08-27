package com.nuvio.tv.playback.mediasession

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanMediaSessionMetadataTest {
    @Test
    fun `ingress metadata strips controls and rejects transport shaped secrets`() {
        val metadata = CleanMediaSessionMetadata.fromIngress(
            redactedContentFingerprint = "https://provider.test/live?token=secret",
            title = "News\u0000  Channel",
            subtitle = "https://provider.test/live?token=secret",
            station = "Authorization: Bearer secret",
        )

        assertEquals("clean-playback", metadata.safeMediaId)
        assertEquals("News Channel", metadata.title)
        assertNull(metadata.subtitle)
        assertNull(metadata.station)
        assertTrue(metadata.toString().contains("hasSubtitle=false"))
        assertTrue("secret" !in metadata.toString())
    }

    @Test
    fun `blank or suspicious title falls back to product label`() {
        val blank = CleanMediaSessionMetadata.fromIngress("ab12ab12ab12ab12", "\n\t")
        val suspicious = CleanMediaSessionMetadata.fromIngress(
            "ab12ab12ab12ab12",
            "https://provider.test/live?password=secret",
        )

        assertEquals("Tuvora", blank.title)
        assertEquals("Tuvora", suspicious.title)
    }
}
