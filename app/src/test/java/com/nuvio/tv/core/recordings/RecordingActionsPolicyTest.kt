package com.nuvio.tv.core.recordings

import com.nuvio.tv.core.recordings.api.RecordingAction
import com.nuvio.tv.core.recordings.api.RecordingActionsPolicy
import com.nuvio.tv.core.recordings.api.RecordingRow
import com.nuvio.tv.core.recordings.api.RecordingSection
import com.nuvio.tv.core.recordings.api.RecordingStatus
import com.nuvio.tv.core.recordings.api.CaptureMode
import org.junit.Test
import org.junit.Assert.assertEquals

class RecordingActionsPolicyTest {
    private fun row(section: RecordingSection, status: RecordingStatus, canRetry: Boolean = false) = RecordingRow(
        id = "x", section = section, title = "T", episode = null, channelName = "C", programmeStartMs = 0, programmeEndMs = 1,
        sizeBytes = 0, status = status, mode = CaptureMode.CATCH_UP, problem = null, description = null,
        scheduleId = "s", recordingId = "r", canRetry = canRetry, retryPending = false,
    )

    @Test
    fun `scheduled and recording rows can be cancelled and nothing else`() {
        assertEquals(listOf(RecordingAction.CANCEL), RecordingActionsPolicy.actionsFor(row(RecordingSection.SCHEDULED, RecordingStatus.SCHEDULED), false))
        assertEquals(listOf(RecordingAction.CANCEL), RecordingActionsPolicy.actionsFor(row(RecordingSection.RECORDING, RecordingStatus.RECORDING), false))
    }

    @Test
    fun `a done row plays when its file is there and always deletes`() {
        val r = row(RecordingSection.DONE, RecordingStatus.DONE)
        assertEquals(listOf(RecordingAction.PLAY, RecordingAction.DELETE), RecordingActionsPolicy.actionsFor(r, fileAvailable = true))
        assertEquals(listOf(RecordingAction.DELETE), RecordingActionsPolicy.actionsFor(r, fileAvailable = false))
    }

    @Test
    fun `a failed or cancelled row retries only when it can and always deletes`() {
        listOf(RecordingStatus.FAILED, RecordingStatus.CANCELLED).forEach { status ->
            assertEquals("$status", listOf(RecordingAction.RETRY, RecordingAction.DELETE), RecordingActionsPolicy.actionsFor(row(RecordingSection.FAILED, status, canRetry = true), false))
            assertEquals("$status", listOf(RecordingAction.DELETE), RecordingActionsPolicy.actionsFor(row(RecordingSection.FAILED, status, canRetry = false), false))
        }
    }
}
