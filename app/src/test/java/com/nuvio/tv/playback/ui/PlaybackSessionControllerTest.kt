package com.nuvio.tv.playback.ui

import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.PlaybackCommand
import com.nuvio.tv.playback.core.PlaybackPreferences
import com.nuvio.tv.playback.core.PlaybackRequest
import com.nuvio.tv.playback.core.PlaybackSnapshot
import com.nuvio.tv.playback.core.ProviderPlaybackSelection
import com.nuvio.tv.playback.core.ProviderSelectionId
import com.nuvio.tv.playback.core.ProviderSourceType
import com.nuvio.tv.playback.core.SessionProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSessionControllerTest {
    @Test
    fun `controller exposes the same immutable snapshot and maps UI commands`() = runTest {
        val snapshots = MutableStateFlow(PlaybackSnapshot())
        val commands = mutableListOf<PlaybackCommand>()
        var released = false
        val controller = PlaybackSessionController(
            snapshot = snapshots,
            dispatchCommand = commands::add,
            releaseSession = { released = true },
        )
        val selection = liveSelection()
        val request = PlaybackRequest("https://example.test/live", contentType = ContentType.LIVE)
        val preferences = PlaybackPreferences.recommended()

        assertSame(snapshots, controller.snapshot)
        controller.tune(selection, SessionProfile.GUIDE)
        controller.zap(selection, SessionProfile.FULLSCREEN)
        controller.tune(request, SessionProfile.FULLSCREEN)
        controller.zap(request, SessionProfile.GUIDE)
        controller.pause()
        controller.resume()
        controller.retry()
        controller.changePreferences(preferences)
        controller.changeProfile(SessionProfile.FULLSCREEN)
        controller.surfaceAvailable()
        controller.surfaceUnavailable()
        controller.stop()
        controller.release()

        assertEquals(12, commands.size)
        assertTrue((commands[0] as PlaybackCommand.Tune).providerSelection === selection)
        assertTrue((commands[1] as PlaybackCommand.Zap).providerSelection === selection)
        assertTrue((commands[2] as PlaybackCommand.Tune).request === request)
        assertTrue((commands[3] as PlaybackCommand.Zap).request === request)
        assertEquals(PlaybackCommand.Pause, commands[4])
        assertEquals(PlaybackCommand.Resume, commands[5])
        assertEquals(PlaybackCommand.Retry, commands[6])
        assertEquals(PlaybackCommand.PreferencesChanged(preferences), commands[7])
        assertEquals(PlaybackCommand.SessionProfileChanged(SessionProfile.FULLSCREEN), commands[8])
        assertEquals(PlaybackCommand.SurfaceAvailable, commands[9])
        assertEquals(PlaybackCommand.SurfaceUnavailable, commands[10])
        assertEquals(PlaybackCommand.Stop, commands[11])
        assertTrue(released)
    }

    private fun liveSelection() = ProviderPlaybackSelection(
        sourceType = ProviderSourceType.XTREAM,
        accountId = ProviderSelectionId("account"),
        itemId = ProviderSelectionId("item"),
        contentKey = ProviderSelectionId("content"),
        contentType = ContentType.LIVE,
    )
}
