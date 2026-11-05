package com.nuvio.tv.core.recordings

import com.nuvio.tv.core.recordings.api.RecordingGuideKeys
import com.nuvio.tv.core.recordings.api.RecordingStatus
import com.nuvio.tv.core.recordings.api.RecordingsState
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class RecordingGuideKeysTest {
    @Test
    fun `scheduled programmes are grouped by channel key and start`() {
        val state = RecordingsState(
            schedules = listOf(
                schedule(id = "a", start = NOW + HOUR, ch = channel(key = "c1")),
                schedule(id = "b", start = NOW + 2 * HOUR, ch = channel(key = "c1")),
                schedule(id = "c", start = NOW + HOUR, ch = channel(key = "c2")),
            ),
            loaded = true,
        )
        assertEquals(mapOf("c1" to setOf(NOW + HOUR, NOW + 2 * HOUR), "c2" to setOf(NOW + HOUR)), RecordingGuideKeys.startsByChannel(state))
    }

    @Test
    fun `a capturing recording keeps its badge and finished or failed ones lose it`() {
        val state = RecordingsState(
            recordings = listOf(
                recording("a", status = RecordingStatus.RECORDING, start = NOW, channelKey = "c1"),
                recording("b", status = RecordingStatus.DONE, start = NOW + HOUR, channelKey = "c1"),
                recording("c", status = RecordingStatus.FAILED, start = NOW + 2 * HOUR, channelKey = "c1"),
            ),
            loaded = true,
        )
        assertEquals(mapOf("c1" to setOf(NOW)), RecordingGuideKeys.startsByChannel(state))
    }

    @Test
    fun `no recordings means no badges`() {
        assertTrue(RecordingGuideKeys.startsByChannel(RecordingsState(loaded = true)).isEmpty())
    }
}
