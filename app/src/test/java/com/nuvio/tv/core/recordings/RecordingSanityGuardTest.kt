package com.nuvio.tv.core.recordings

import com.nuvio.tv.core.recordings.api.RecordingProblem
import com.nuvio.tv.core.recordings.internal.RecordingSanityGuard as G
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSanityGuardTest {
    private val playlist = "#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray()
    private val junk = "<html>oops</html>".toByteArray()

    @Test
    fun `a kilobyte for a half hour programme is implausible`() {
        assertTrue(G.implausiblyTiny(30 * MIN, 1_024))
        assertTrue("just under the floor", G.implausiblyTiny(60_000, 64L * 1024 - 1))
    }

    @Test
    fun `a short programme or a real file is fine`() {
        assertFalse("30 seconds", G.implausiblyTiny(30_000, 1_024))
        assertFalse("unknown length", G.implausiblyTiny(0, 1_024))
        assertFalse("at the floor", G.implausiblyTiny(30 * MIN, 64L * 1024))
        assertFalse("200 MB", G.implausiblyTiny(30 * MIN, 200L * 1024 * 1024))
    }

    @Test
    fun `a tiny playlist is unsupported and other tiny bodies are retryable`() {
        assertEquals(RecordingProblem.UNSUPPORTED_FORMAT, G.verdict(30 * MIN, 900, playlist))
        assertEquals(RecordingProblem.NETWORK, G.verdict(30 * MIN, 900, junk))
        assertNull(G.verdict(30_000, 900, junk))
        assertNull(G.verdict(30 * MIN, 200L * 1024 * 1024, junk))
    }
}
