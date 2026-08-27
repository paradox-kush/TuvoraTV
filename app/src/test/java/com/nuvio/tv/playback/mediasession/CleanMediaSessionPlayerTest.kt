package com.nuvio.tv.playback.mediasession

import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.PlaybackCommand
import com.nuvio.tv.playback.core.PlaybackSnapshot
import com.nuvio.tv.playback.core.PlaybackState
import com.nuvio.tv.playback.core.RequestSummary
import com.nuvio.tv.playback.core.CrossHostAuthorization
import com.nuvio.tv.playback.core.DnsPolicy
import com.nuvio.tv.playback.core.ProxyMode
import com.nuvio.tv.playback.core.RedirectPolicy
import com.nuvio.tv.playback.core.TlsPolicy
import com.nuvio.tv.playback.core.TransientLoadRetryPolicy
import com.nuvio.tv.playback.ui.PlaybackSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class CleanMediaSessionPlayerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val snapshots = kotlinx.coroutines.flow.MutableStateFlow(PlaybackSnapshot())
    private val commands = mutableListOf<PlaybackCommand>()
    private val controller = PlaybackSessionController(
        snapshot = snapshots,
        dispatchCommand = commands::add,
        releaseSession = {},
    )
    private val playerDelegate = lazy {
        CleanMediaSessionPlayer(
            applicationLooper = Looper.getMainLooper(),
            parentScope = scope,
            controller = controller,
            metadata = CleanMediaSessionMetadata.fromIngress(
                redactedContentFingerprint = "ab12ab12ab12ab12",
                title = "News",
                subtitle = "Live",
                station = "Tuvora TV",
            ),
        )
    }
    private val player by playerDelegate

    @After
    fun tearDown() {
        if (playerDelegate.isInitialized() && Looper.myLooper() == Looper.getMainLooper()) {
            player.release()
        }
        scope.cancel()
    }

    @Test
    fun `snapshot is projected without a media URI and updates remain engine neutral`() {
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertEquals("News", player.mediaMetadata.title)
        assertEquals("clean-ab12ab12ab12ab12", player.currentMediaItem?.mediaId)
        assertNull(player.currentMediaItem?.localConfiguration)

        snapshots.value = PlaybackSnapshot(
            generation = 4,
            state = PlaybackState.PLAYING,
            requestSummary = liveSummary(),
            playWhenReady = true,
            isPlaying = true,
            positionMs = 12_345,
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(Player.STATE_READY, player.playbackState)
        assertTrue(player.playWhenReady)
        assertEquals(12_345, player.currentPosition)
        assertTrue(player.currentTimeline.getWindow(0, androidx.media3.common.Timeline.Window()).isLive)
        assertNull(player.currentMediaItem?.localConfiguration)
    }

    @Test
    fun `explicit play intent remains true while playback is buffering`() {
        snapshots.value = PlaybackSnapshot(
            generation = 5,
            state = PlaybackState.LIVE_RECONNECTING,
            requestSummary = liveSummary(),
            playWhenReady = true,
            isPlaying = false,
            isBuffering = true,
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(Player.STATE_BUFFERING, player.playbackState)
        assertTrue(player.playWhenReady)
        assertTrue(player.isLoading)
    }

    @Test
    fun `only play pause and stop mutate the clean session controller`() {
        assertTrue(player.availableCommands.contains(Player.COMMAND_PLAY_PAUSE))
        assertTrue(player.availableCommands.contains(Player.COMMAND_STOP))
        assertFalse(player.availableCommands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        assertFalse(player.availableCommands.contains(Player.COMMAND_SET_MEDIA_ITEM))

        player.play()
        player.pause()
        player.stop()

        assertEquals(
            listOf(PlaybackCommand.Resume, PlaybackCommand.Pause, PlaybackCommand.Stop),
            commands,
        )
    }

    @Test
    fun `metadata update changes display item without introducing a URI`() {
        player.updateMetadata(
            CleanMediaSessionMetadata.fromIngress(
                redactedContentFingerprint = "cd34cd34cd34cd34",
                title = "Sports",
                station = "Tuvora TV",
            ),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("clean-cd34cd34cd34cd34", player.currentMediaItem?.mediaId)
        assertEquals("Sports", player.mediaMetadata.title)
        assertNull(player.currentMediaItem?.localConfiguration)
    }

    private fun liveSummary() = RequestSummary(
        scheme = "https",
        contentType = ContentType.LIVE,
        hasAuthorization = false,
        hasCustomHeaders = false,
        hasCookies = false,
        hasUserAgent = false,
        hasReferer = false,
        hasOrigin = false,
        hasDrm = false,
        redirectPolicy = RedirectPolicy.FOLLOW,
        crossHostAuthorization = CrossHostAuthorization.STRIP,
        tlsPolicy = TlsPolicy.STRICT,
        dnsPolicy = DnsPolicy.SYSTEM,
        proxyMode = ProxyMode.SYSTEM,
        transientLoadRetryPolicy = TransientLoadRetryPolicy.SESSION_ONLY,
        providerConnectionConstrained = true,
    )
}
