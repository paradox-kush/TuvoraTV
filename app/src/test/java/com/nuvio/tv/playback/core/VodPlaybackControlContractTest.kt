package com.nuvio.tv.playback.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VodPlaybackControlContractTest {
    @Test
    fun `VOD tune carries explicit resume position into the new generation`() {
        val transition = PlaybackStateMachine.reduce(
            PlaybackMachineState(surfaceAvailable = true),
            PlaybackCommand.Tune(
                request = vodRequest(),
                profile = SessionProfile.FULLSCREEN,
                startPositionMs = 42_000,
            ),
        )

        assertEquals(42_000L, transition.state.snapshot.positionMs)
        assertNull(transition.state.snapshot.completionReason)
        assertTrue(transition.actions.single() is PlaybackAction.ResolveRequest)
    }

    @Test
    fun `timeline facts are generation bound and expose the complete VOD timeline`() {
        val state = playingState()
        val facts = PlaybackTimelineFacts(
            positionMs = 12_000,
            durationMs = 90_000,
            bufferedPositionMs = 30_000,
            seekable = true,
        )

        val stale = PlaybackStateMachine.reduce(state, PlaybackEvent.TimelineUpdated(6, facts))
        assertEquals(0L, stale.state.snapshot.positionMs)

        val current = PlaybackStateMachine.reduce(state, PlaybackEvent.TimelineUpdated(7, facts))
        assertEquals(12_000L, current.state.snapshot.positionMs)
        assertEquals(90_000L, current.state.snapshot.durationMs)
        assertEquals(30_000L, current.state.snapshot.bufferedPositionMs)
        assertTrue(current.state.snapshot.seekable)
    }

    @Test
    fun `VOD playback controls remain engine neutral reducer actions`() {
        val state = playingState()
        val audioId = PlaybackTrackId("audio-main")
        val subtitleId = PlaybackTrackId("subtitle-en")
        val externalId = ExternalSubtitleId("registered-1")

        assertEquals(
            PlaybackAction.SeekTo(7, 25_000),
            PlaybackStateMachine.reduce(state, PlaybackCommand.SeekTo(25_000)).actions.single(),
        )
        assertEquals(
            PlaybackAction.SetPlaybackRate(7, 1.5f),
            PlaybackStateMachine.reduce(state, PlaybackCommand.SetPlaybackRate(1.5f)).actions.single(),
        )
        assertEquals(
            PlaybackAction.SelectAudioTrack(7, audioId),
            PlaybackStateMachine.reduce(state, PlaybackCommand.SelectAudioTrack(audioId)).actions.single(),
        )
        assertEquals(
            PlaybackAction.SelectSubtitleTrack(7, subtitleId),
            PlaybackStateMachine.reduce(state, PlaybackCommand.SelectSubtitleTrack(subtitleId)).actions.single(),
        )
        assertEquals(
            PlaybackAction.SetSubtitlesEnabled(7, false),
            PlaybackStateMachine.reduce(state, PlaybackCommand.DisableSubtitles).actions.single(),
        )
        assertEquals(
            PlaybackAction.AttachExternalSubtitle(7, externalId),
            PlaybackStateMachine.reduce(
                state,
                PlaybackCommand.AttachExternalSubtitle(externalId),
            ).actions.single(),
        )
    }

    @Test
    fun `track catalog and rate facts reject stale generations`() {
        val state = playingState()
        val audio = PlaybackTrackDescriptor(
            id = PlaybackTrackId("audio-en"),
            type = PlaybackTrackType.AUDIO,
            language = "en",
        )
        val catalog = PlaybackTrackCatalog(
            revision = 1,
            audio = listOf(audio),
            selectedAudioTrackId = audio.id,
        )

        val staleCatalog = PlaybackStateMachine.reduce(
            state,
            PlaybackEvent.TrackCatalogUpdated(8, catalog),
        )
        assertTrue(staleCatalog.state.snapshot.trackCatalog.audio.isEmpty())

        val currentCatalog = PlaybackStateMachine.reduce(
            state,
            PlaybackEvent.TrackCatalogUpdated(7, catalog),
        )
        assertEquals(catalog, currentCatalog.state.snapshot.trackCatalog)

        val staleRate = PlaybackStateMachine.reduce(
            state,
            PlaybackEvent.PlaybackRateChanged(8, 1.5f),
        )
        assertEquals(1f, staleRate.state.snapshot.playbackRate)
        val currentRate = PlaybackStateMachine.reduce(
            state,
            PlaybackEvent.PlaybackRateChanged(7, 1.5f),
        )
        assertEquals(1.5f, currentRate.state.snapshot.playbackRate)
    }

    @Test
    fun `VOD EOF records completion while stop does not manufacture completion`() {
        val eof = PlaybackStateMachine.reduce(
            playingState(),
            PlaybackEvent.PlaybackEnded(7, PlaybackEndReason.EOF),
        )
        assertEquals(PlaybackCompletionReason.EOF, eof.state.snapshot.completionReason)
        assertFalse(eof.state.sessionActive)
        assertTrue(eof.actions.single() is PlaybackAction.ReleaseActiveWork)

        val stopped = PlaybackStateMachine.reduce(playingState(), PlaybackCommand.Stop)
        assertNull(stopped.state.snapshot.completionReason)
    }

    @Test
    fun `external subtitle registry exposes opaque ids and clears destination transport`() {
        val registry = DestinationExternalSubtitleRegistry()
        val registration = ExternalSubtitleRegistration(
            uri = "https://subtitle.example/file.srt?token=secret",
            mimeType = "application/x-subrip",
            language = "en",
        )

        val id = registry.register(registration)

        assertEquals(registration, registry.resolve(id))
        assertFalse(id.toString().contains(id.value))
        assertFalse(registration.toString().contains("token=secret"))
        registry.clear()
        assertNull(registry.resolve(id))
    }

    private fun playingState() = PlaybackMachineState(
        snapshot = PlaybackSnapshot(
            generation = 7,
            state = PlaybackState.PLAYING,
            graph = graph(),
            playWhenReady = true,
            isPlaying = true,
        ),
        launch = PlaybackLaunch.ConcreteRequest(vodRequest()),
        request = vodRequest(),
        surfaceAvailable = true,
    )

    private fun vodRequest() = PlaybackRequest(
        url = "https://example.invalid/movie.mp4",
        contentType = ContentType.VOD,
    )

    private fun graph() = PlaybackGraph(
        id = "media3-vod",
        engine = EngineType.MEDIA3,
        outputProfile = GraphOutputProfile.MEDIA3_STANDARD,
        decoderMode = DecoderMode.HARDWARE,
        audioMode = AudioMode.DECODE,
        surfaceMode = SurfaceMode.SURFACE_VIEW,
        secureOutput = false,
    )
}
