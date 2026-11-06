package com.nuvio.tv.ui.screens.iptv.recordings

import com.nuvio.tv.core.recordings.api.RecordingAction
import com.nuvio.tv.core.recordings.api.RecordingStatus
import com.nuvio.tv.ui.screens.iptv.recordings.RecordingDetailsFocusPolicy.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingDetailsFocusPolicyTest {

    private fun actions(status: RecordingStatus, fileAvailable: Boolean, canRetry: Boolean): List<RecordingAction> = when (status) {
        RecordingStatus.SCHEDULED, RecordingStatus.RECORDING -> listOf(RecordingAction.CANCEL)
        RecordingStatus.DONE -> if (fileAvailable) listOf(RecordingAction.PLAY, RecordingAction.DELETE) else listOf(RecordingAction.DELETE)
        RecordingStatus.FAILED, RecordingStatus.CANCELLED ->
            if (canRetry) listOf(RecordingAction.RETRY, RecordingAction.DELETE) else listOf(RecordingAction.DELETE)
    }

    @Test
    fun `a saved recording with its file starts on Play`() {
        assertEquals(Target.PLAY, RecordingDetailsFocusPolicy.defaultFocus(RecordingStatus.DONE, actions(RecordingStatus.DONE, true, false)))
    }

    @Test
    fun `scheduled recording and failed rows start on Close, not on Cancel or Delete`() {
        listOf(RecordingStatus.SCHEDULED, RecordingStatus.RECORDING, RecordingStatus.FAILED, RecordingStatus.CANCELLED).forEach { status ->
            listOf(true, false).forEach { retry ->
                assertEquals("$status retry=$retry", Target.CLOSE, RecordingDetailsFocusPolicy.defaultFocus(status, actions(status, true, retry)))
            }
        }
    }

    @Test
    fun `a saved recording whose file is gone starts on Close, not on Delete`() {
        assertEquals(Target.CLOSE, RecordingDetailsFocusPolicy.defaultFocus(RecordingStatus.DONE, actions(RecordingStatus.DONE, false, false)))
    }

    @Test
    fun `no status and action combination defaults to a destructive action`() {
        for (status in RecordingStatus.values()) for (file in listOf(true, false)) for (retry in listOf(true, false)) {
            val target = RecordingDetailsFocusPolicy.defaultFocus(status, actions(status, file, retry))
            assertTrue("$status file=$file retry=$retry -> $target", target == Target.PLAY || target == Target.CLOSE)
            if (target == Target.PLAY) assertTrue("Play only for a playable Done row", status == RecordingStatus.DONE && file)
        }
        assertFalse(Target.values().any { it.name == "DELETE" || it.name == "CANCEL" })
    }
}
