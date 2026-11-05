package com.nuvio.tv.playback.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveReconnectPressureResetPolicyTest {

    private val channelA = PlaybackRequest("https://example.invalid/live-a", contentType = ContentType.LIVE)
    private val channelB = PlaybackRequest("https://example.invalid/live-b", contentType = ContentType.LIVE)

    private fun stalker(contentKey: String) = ProviderPlaybackSelection(
        sourceType = ProviderSourceType.STALKER,
        accountId = ProviderSelectionId("portal|mac"),
        itemId = ProviderSelectionId("42"),
        contentKey = ProviderSelectionId(contentKey),
        contentType = ContentType.LIVE,
    )

    @Test
    fun `user retry always starts a fresh window`() {
        assertTrue(
            "Retry",
            LiveReconnectPressureResetPolicy.resetsOn(PlaybackCommand.Retry, PlaybackLaunch.ConcreteRequest(channelA)),
        )
    }

    @Test
    fun `zap or tune to a different channel starts a fresh window`() {
        val current = PlaybackLaunch.ConcreteRequest(channelA)
        assertTrue("zap", LiveReconnectPressureResetPolicy.resetsOn(PlaybackCommand.Zap(channelB, SessionProfile.FULLSCREEN), current))
        assertTrue("tune", LiveReconnectPressureResetPolicy.resetsOn(PlaybackCommand.Tune(channelB, SessionProfile.GUIDE), current))
        assertTrue(
            "provider channel change",
            LiveReconnectPressureResetPolicy.resetsOn(
                PlaybackCommand.Zap(stalker("live:2"), SessionProfile.FULLSCREEN),
                PlaybackLaunch.DeferredProvider(stalker("live:1")),
            ),
        )
        assertTrue(
            "first tune of a session",
            LiveReconnectPressureResetPolicy.resetsOn(PlaybackCommand.Tune(channelA, SessionProfile.FULLSCREEN), null),
        )
    }

    @Test
    fun `re-issuing the channel already playing keeps the pressure`() {
        assertFalse(
            "same provider stream",
            LiveReconnectPressureResetPolicy.resetsOn(
                PlaybackCommand.Zap(stalker("live:1"), SessionProfile.FULLSCREEN),
                PlaybackLaunch.DeferredProvider(stalker("live:1")),
            ),
        )
        assertFalse(
            "same concrete stream",
            LiveReconnectPressureResetPolicy.resetsOn(
                PlaybackCommand.Tune(channelA, SessionProfile.FULLSCREEN),
                PlaybackLaunch.ConcreteRequest(channelA),
            ),
        )
    }

    @Test
    fun `the pipeline's own commands never reset pressure`() {
        val current = PlaybackLaunch.ConcreteRequest(channelA)
        listOf(
            PlaybackCommand.Resume,
            PlaybackCommand.Pause,
            PlaybackCommand.SurfaceAvailable,
            PlaybackCommand.SurfaceUnavailable,
            PlaybackCommand.Stop,
        ).forEach { command ->
            assertFalse(command.toString(), LiveReconnectPressureResetPolicy.resetsOn(command, current))
        }
    }
}
