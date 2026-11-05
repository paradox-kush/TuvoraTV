package com.nuvio.tv.core.recordings

import com.nuvio.tv.core.recordings.api.RecordingsLoadAttempt
import com.nuvio.tv.core.recordings.api.RecordingsLoadPolicy
import com.nuvio.tv.core.recordings.api.RecordingsView
import org.junit.Test
import org.junit.Assert.assertEquals

class RecordingsLoadPolicyTest {
    private val start = 1_000_000L

    @Test
    fun `loaded state is ready whatever the attempt did`() {
        assertEquals(RecordingsView.Ready, RecordingsLoadPolicy.viewAt(true, RecordingsLoadAttempt(start), start))
        assertEquals(RecordingsView.Ready, RecordingsLoadPolicy.viewAt(true, RecordingsLoadAttempt(start, failed = true), start + 999_999))
    }

    @Test
    fun `an unloaded state inside the deadline is loading and carries its deadline`() {
        val view = RecordingsLoadPolicy.viewAt(false, RecordingsLoadAttempt(start), start + 1_000)
        assertEquals(RecordingsView.Loading(start + RecordingsLoadPolicy.DEADLINE_MS), view)
    }

    @Test
    fun `an unloaded state at the deadline is failed as timed out`() {
        val view = RecordingsLoadPolicy.viewAt(false, RecordingsLoadAttempt(start), start + RecordingsLoadPolicy.DEADLINE_MS)
        assertEquals(RecordingsView.Failed(timedOut = true), view)
    }

    @Test
    fun `a read that threw is failed at once and never empty`() {
        val view = RecordingsLoadPolicy.viewAt(false, RecordingsLoadAttempt(start, failed = true), start + 10)
        assertEquals(RecordingsView.Failed(timedOut = false), view)
    }

    @Test
    fun `retry starts a new attempt with a fresh deadline`() {
        val retry = RecordingsLoadAttempt(startedAtMs = start + 50_000)
        assertEquals(RecordingsView.Loading(start + 50_000 + RecordingsLoadPolicy.DEADLINE_MS), RecordingsLoadPolicy.viewAt(false, retry, start + 50_001))
    }
}
