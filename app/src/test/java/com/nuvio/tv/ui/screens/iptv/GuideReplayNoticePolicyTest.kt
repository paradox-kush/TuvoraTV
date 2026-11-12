package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.XtreamProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GuideReplayNoticePolicyTest {
    private val channel = GuideChannel("xtream:list:live:5", "BBC", null, "", 5)
    private val programme = XtreamProgram("Show", "", 1_000L, 2_000L, nowPlaying = false)
    private val idle = LiveGuideUiState(focusedChannelId = channel.contentId)

    @Test fun `a refused replay carries Retry for the same channel and programme`() {
        val next = GuideReplayNoticePolicy.refused(idle, "This portal is already streaming on another device", channel, programme)
        assertEquals("message", "This portal is already streaming on another device", next.actionError)
        val retry = next.replayRetry
        assertNotNull("a refusal must offer Retry", retry)
        assertEquals("retry channel", channel, retry!!.channel)
        assertEquals("retry programme", programme, retry.programme)
    }

    @Test fun `a request failure is retryable too`() {
        val next = GuideReplayNoticePolicy.refused(idle, GuideReplayMessages.openFailed("Show"), channel, programme)
        assertNotNull("an open failure must offer Retry", next.replayRetry)
    }

    @Test fun `a programme with no recording says so without Retry`() {
        val next = GuideReplayNoticePolicy.noRecording(idle, GuideReplayMessages.noRecording("Show"))
        assertEquals("message", GuideReplayMessages.noRecording("Show"), next.actionError)
        assertNull("nothing to retry when the provider has no recording", next.replayRetry)
    }

    @Test fun `dismissing clears both the message and Retry`() {
        val shown = GuideReplayNoticePolicy.refused(idle, "x", channel, programme)
        val next = GuideReplayNoticePolicy.dismiss(shown)
        assertNull("message", next.actionError)
        assertNull("retry", next.replayRetry)
    }

    @Test fun `moving focus drops Retry with the message`() {
        val shown = GuideReplayNoticePolicy.refused(idle, "x", channel, programme)
        val other = GuideChannel("other", "Other", null, "", 6)
        val next = GuidePlaybackInteractionPolicy.focus(shown.copy(channels = listOf(channel, other)), "other")
        assertNull("message", next.actionError)
        assertNull("retry", next.replayRetry)
    }

    @Test fun `retrying consumes the notice so a second refusal can show again`() {
        val shown = GuideReplayNoticePolicy.refused(idle, "x", channel, programme)
        val retried = GuideReplayNoticePolicy.dismiss(shown)
        val again = GuideReplayNoticePolicy.refused(retried, "x", channel, programme)
        assertNotNull("retry", again.replayRetry)
    }
}
