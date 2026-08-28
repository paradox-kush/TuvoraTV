package com.nuvio.tv.playback.ui

import com.nuvio.tv.playback.core.ExternalSubtitleId
import com.nuvio.tv.playback.core.ExternalSubtitleRegistration
import com.nuvio.tv.playback.core.PlaybackCompletionReason
import com.nuvio.tv.playback.core.PlaybackProfileId
import com.nuvio.tv.playback.core.PlaybackRequest
import com.nuvio.tv.playback.core.PlaybackSnapshot
import com.nuvio.tv.playback.core.PlaybackState
import com.nuvio.tv.playback.core.PlaybackTrackCatalog
import com.nuvio.tv.playback.core.PlaybackTrackDescriptor
import com.nuvio.tv.playback.core.PlaybackTrackId
import com.nuvio.tv.playback.core.PlaybackTrackType
import com.nuvio.tv.playback.host.CleanVodHost
import com.nuvio.tv.playback.mediasession.CleanMediaSessionMetadata
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VodPlaybackPresentationBridgeTest {
    @Test
    fun `presentation maps clean timeline tracks and EOF to legacy-compatible facts`() {
        val audio = track("audio-en", PlaybackTrackType.AUDIO)
        val subtitle = track("sub-es", PlaybackTrackType.SUBTITLE)
        val state = VodPlaybackPresentationBridge.present(
            PlaybackSnapshot(
                generation = 4,
                state = PlaybackState.STOPPED,
                positionMs = 80_000,
                durationMs = 80_000,
                bufferedPositionMs = 80_000,
                seekable = true,
                playbackRate = 1.25f,
                trackCatalog = PlaybackTrackCatalog(
                    revision = 2,
                    audio = listOf(audio),
                    subtitles = listOf(subtitle),
                    selectedAudioTrackId = audio.id,
                    selectedSubtitleTrackId = subtitle.id,
                    subtitlesEnabled = true,
                ),
                completionReason = PlaybackCompletionReason.EOF,
            ),
        )

        assertTrue(state.playbackEnded)
        assertEquals(80_000L, state.positionMs)
        assertEquals(1.25f, state.playbackRate)
        assertEquals(0, state.selectedAudioIndex)
        assertEquals(0, state.selectedSubtitleIndex)
    }

    @Test
    fun `index based legacy controls resolve to stable clean track ids`() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val audio = track("audio-en", PlaybackTrackType.AUDIO)
        val subtitle = track("sub-es", PlaybackTrackType.SUBTITLE)
        val host = FakeVodHost(
            PlaybackSnapshot(
                generation = 2,
                state = PlaybackState.PLAYING,
                playWhenReady = true,
                trackCatalog = PlaybackTrackCatalog(
                    revision = 1,
                    audio = listOf(audio),
                    subtitles = listOf(subtitle),
                    selectedAudioTrackId = audio.id,
                    subtitlesEnabled = true,
                ),
            ),
        )
        val bridge = VodPlaybackPresentationBridge(host, scope)
        bridge.start()
        bridge.dispatch(VodPlaybackIntent.SelectAudioIndex(0))
        bridge.dispatch(VodPlaybackIntent.SelectSubtitleIndex(0))
        bridge.dispatch(VodPlaybackIntent.SeekTo(12_000))
        bridge.dispatch(VodPlaybackIntent.SetPlaybackRate(1.5f))
        scope.advanceUntilIdle()

        assertEquals(audio.id, host.selectedAudio)
        assertEquals(subtitle.id, host.selectedSubtitle)
        assertEquals(12_000L, host.seekPosition)
        assertEquals(1.5f, host.rate)
    }

    private fun track(id: String, type: PlaybackTrackType) = PlaybackTrackDescriptor(
        id = PlaybackTrackId(id),
        type = type,
        language = "en",
    )

    private class FakeVodHost(initial: PlaybackSnapshot) : CleanVodHost {
        override val snapshot = MutableStateFlow(initial)
        var selectedAudio: PlaybackTrackId? = null
        var selectedSubtitle: PlaybackTrackId? = null
        var seekPosition: Long? = null
        var rate: Float? = null

        override suspend fun tune(
            request: PlaybackRequest,
            startPositionMs: Long,
            metadata: CleanMediaSessionMetadata,
        ) = 1L
        override suspend fun pause() = Unit
        override suspend fun resume() = Unit
        override suspend fun retry() = Unit
        override suspend fun seekTo(positionMs: Long) { seekPosition = positionMs }
        override suspend fun setPlaybackRate(rate: Float) { this.rate = rate }
        override suspend fun selectAudioTrack(trackId: PlaybackTrackId) { selectedAudio = trackId }
        override suspend fun selectSubtitleTrack(trackId: PlaybackTrackId) { selectedSubtitle = trackId }
        override suspend fun disableSubtitles() = Unit
        override suspend fun attachExternalSubtitle(subtitleId: ExternalSubtitleId) = Unit
        override fun registerExternalSubtitle(
            registration: ExternalSubtitleRegistration,
        ): ExternalSubtitleId = ExternalSubtitleId("test-subtitle")
        override suspend fun stop() = Unit
        override suspend fun release() = Unit
    }
}
