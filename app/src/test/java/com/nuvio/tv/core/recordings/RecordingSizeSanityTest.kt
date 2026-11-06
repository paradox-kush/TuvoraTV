package com.nuvio.tv.core.recordings

import com.nuvio.tv.core.recordings.api.RecordingProblem
import com.nuvio.tv.core.recordings.internal.RecordingSizeSanity as S
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSizeSanityTest {
    @Test
    fun `a kilobyte for a half hour programme is implausible`() {
        assertTrue(S.isImplausiblySmall(1_024, 30 * MIN))
        assertTrue("just under the floor", S.isImplausiblySmall(64L * 1024 - 1, 60_000))
    }

    @Test
    fun `a short programme or a real file is fine`() {
        assertFalse("30 seconds", S.isImplausiblySmall(1_024, 30_000))
        assertFalse("unknown length", S.isImplausiblySmall(1_024, 0))
        assertFalse("at the floor", S.isImplausiblySmall(64L * 1024, 30 * MIN))
        assertFalse("200 MB", S.isImplausiblySmall(200L * 1024 * 1024, 30 * MIN))
    }

    @Test
    fun `a tiny playlist is unsupported and other tiny bodies are retryable`() {
        assertEquals(RecordingProblem.UNSUPPORTED_FORMAT, S.problemFor(900, 30 * MIN, startsWithPlaylistTag = true))
        assertEquals(RecordingProblem.NETWORK, S.problemFor(900, 30 * MIN, startsWithPlaylistTag = false))
        assertNull(S.problemFor(900, 30_000, startsWithPlaylistTag = false))
        assertNull(S.problemFor(200L * 1024 * 1024, 30 * MIN, startsWithPlaylistTag = false))
    }
}
