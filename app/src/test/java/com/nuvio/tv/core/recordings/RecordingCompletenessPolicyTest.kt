package com.nuvio.tv.core.recordings

import com.nuvio.tv.core.recordings.internal.RecordingCompletenessPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingCompletenessPolicyTest {
    @Test
    fun `a clean end with no declared length far below the estimate is not a finished save`() {
        assertTrue("5 MB of an expected 2 GB", RecordingCompletenessPolicy.endedTooEarly(5_000_000, null, 2_000_000_000))
    }

    @Test
    fun `a declared length is trusted over the estimate`() {
        assertFalse("length was declared", RecordingCompletenessPolicy.endedTooEarly(5_000_000, 5_000_000, 2_000_000_000))
    }

    @Test
    fun `no estimate means no judgement`() {
        assertFalse("nothing to compare", RecordingCompletenessPolicy.endedTooEarly(10, null, 0))
    }

    @Test
    fun `a plausible size with no declared length is accepted`() {
        assertFalse("900 MB of 2 GB is believable", RecordingCompletenessPolicy.endedTooEarly(900_000_000, null, 2_000_000_000))
    }
}
