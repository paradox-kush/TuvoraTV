package com.nuvio.tv.playback.lab

import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.data.local.LiveChannelRef
import com.nuvio.tv.playback.core.AudioOutputPreference
import com.nuvio.tv.playback.core.BufferingPreference
import com.nuvio.tv.playback.core.DecoderPreference
import com.nuvio.tv.playback.core.EngineType
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.FailureDomain
import com.nuvio.tv.playback.core.FailurePhase
import com.nuvio.tv.playback.core.FrameRatePreference
import com.nuvio.tv.playback.core.HdrPreference
import com.nuvio.tv.playback.core.PlaybackFailure
import com.nuvio.tv.playback.core.PlaybackRequirements
import com.nuvio.tv.playback.core.ResourceBudget
import com.nuvio.tv.playback.core.Retryability
import com.nuvio.tv.playback.core.SessionPriority
import com.nuvio.tv.playback.core.SessionProfile
import com.nuvio.tv.playback.core.SubtitleFidelity
import com.nuvio.tv.playback.core.SurfaceMode
import com.nuvio.tv.playback.core.VideoQualityIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanMedia3PlaybackLabPolicyTest {
    @Test
    fun `fixture selects only the active playlist recent and redacts its representation`() {
        val active = account(id = "active-id", baseUrl = "https://secret.example", username = "private-user")
        val other = account(id = "other-id")
        val selectedId = XtreamItemRegistry.liveId(active.id, 73)

        val result = selectDebugFixture(
            selectedAccountId = active.id,
            accounts = listOf(other, active),
            recents = listOf(recent(other, 19), recent(active, 73)),
        ) as DebugFixtureSelection.Ready

        assertEquals(73, result.fixture.streamId)
        assertEquals(selectedId, result.fixture.contentId)
        val rendered = result.toString()
        assertFalse(rendered.contains(active.baseUrl))
        assertFalse(rendered.contains(active.username))
        assertFalse(rendered.contains(active.password))
        assertFalse(rendered.contains(selectedId))
    }

    @Test
    fun `fixture fails closed and never falls back to another playlist`() {
        val other = account(id = "other-id")

        assertEquals(
            DebugFixtureSelection.Blocked(LabReadinessCode.SELECTED_PLAYLIST_MISSING),
            selectDebugFixture("missing-id", listOf(other), listOf(recent(other, 19))),
        )
        assertEquals(
            DebugFixtureSelection.Blocked(LabReadinessCode.NO_RECENT_LIVE_CHANNEL),
            selectDebugFixture(other.id, listOf(other), emptyList()),
        )
    }

    @Test
    fun `Stalker fixture is rejected before legacy resolution can log provider facts`() {
        val stalker = account(id = "stalker", sourceType = XtreamAccount.SOURCE_STALKER)

        assertEquals(
            DebugFixtureSelection.Blocked(LabReadinessCode.UNSUPPORTED_SOURCE),
            selectDebugFixture(stalker.id, listOf(stalker), listOf(recent(stalker, 3))),
        )
    }

    @Test
    fun `Fire mapped TextureView constraint reaches the lab candidate graph unchanged`() {
        val candidates = media3LabCandidates(
            requirements(allowedSurfaces = setOf(SurfaceMode.TEXTURE_VIEW)),
        )

        assertTrue(candidates.isNotEmpty())
        assertEquals(setOf(SurfaceMode.TEXTURE_VIEW), candidates.map { it.surfaceMode }.toSet())
        assertEquals(setOf(EngineType.MEDIA3), candidates.map { it.engine }.toSet())
    }

    @Test
    fun `libmpv lab materializes only the approved direct surface graph`() {
        val candidates = mpvLabCandidates(
            requirements(setOf(SurfaceMode.NATIVE_EMBED)).copy(
                eligibleEngines = setOf(EngineType.LIBMPV),
            ),
        )

        assertEquals(1, candidates.size)
        assertEquals(EngineType.LIBMPV, candidates.single().engine)
        assertEquals(SurfaceMode.NATIVE_EMBED, candidates.single().surfaceMode)
        assertEquals(com.nuvio.tv.playback.core.GraphOutputProfile.MPV_DIRECT, candidates.single().outputProfile)
    }

    @Test
    fun `libmpv closed facts identify guide engine audio and direct surface`() {
        assertTrue(CleanPlaybackSmokeLine.session(8, EngineType.LIBMPV).contains("profile=GUIDE"))
        assertTrue(
            CleanPlaybackSmokeLine.renderer(
                decoderName = "mediacodec",
                sampleMimeType = "video/hevc",
                engine = EngineType.LIBMPV,
            ).contains("engine=LIBMPV renderer=libmpv decoder=mediacodec codec=HEVC"),
        )
        assertTrue(CleanPlaybackSmokeLine.firstAudio(EngineType.LIBMPV).contains("rendered_first_audio=true"))
        assertTrue(
            CleanPlaybackSmokeLine.surface(SurfaceMode.NATIVE_EMBED, true, 1920, 1080)
                .contains("engine=LIBMPV surface_type=MPV_DIRECT"),
        )
    }

    @Test
    fun `closed state line uses supplied player facts even for terminal states`() {
        val line = CleanPlaybackSmokeLine.state(
            generation = 4,
            state = LabPlayerState.ENDED,
            playWhenReady = false,
            loading = true,
        )

        assertTrue(line.contains("player_state=ENDED"))
        assertTrue(line.contains("play_when_ready=false"))
        assertTrue(line.contains("is_loading=true"))
    }

    @Test
    fun `closed error fatal flag follows normalized retryability`() {
        val recoverable = failure(Retryability.HANDOFF_ELIGIBLE)
        val fatal = failure(Retryability.FATAL)

        assertTrue(CleanPlaybackSmokeLine.error(recoverable).endsWith("fatal=false"))
        assertTrue(CleanPlaybackSmokeLine.error(fatal).endsWith("fatal=true"))
    }

    @Test
    fun `renderer and video facts include only truthful sanitized values`() {
        assertTrue(
            CleanPlaybackSmokeLine.renderer("c2.mtk.avc.decoder", "video/avc")
                .contains("decoder=c2.mtk.avc.decoder codec=AVC"),
        )
        assertFalse(CleanPlaybackSmokeLine.renderer("decoder with spaces").contains("decoder="))
        assertTrue(CleanPlaybackSmokeLine.videoSize(1_920, 1_080).contains("video_width=1920 video_height=1080"))
    }

    @Test
    fun `release line requires and echoes the harness nonce`() {
        val line = CleanPlaybackSmokeLine.release("0123456789abcdef", hardAbort = false)

        assertTrue(line.contains("release_outcome=GRACEFUL"))
        assertTrue(line.contains("release_nonce=0123456789abcdef"))
        runCatching { CleanPlaybackSmokeLine.release("not-a-nonce", hardAbort = false) }
            .onSuccess { throw AssertionError("invalid nonce was accepted") }
    }

    private fun account(
        id: String,
        baseUrl: String = "https://example.test",
        username: String = "user",
        sourceType: String = XtreamAccount.SOURCE_XTREAM,
    ) = XtreamAccount(
        id = id,
        name = "fixture",
        baseUrl = baseUrl,
        username = username,
        password = "private-password",
        sourceType = sourceType,
    )

    private fun recent(account: XtreamAccount, streamId: Int) = LiveChannelRef(
        id = XtreamItemRegistry.liveId(account.id, streamId),
        name = "private channel",
        logo = null,
        streamUrl = "https://do-not-log.example/$streamId",
        playedAt = 1,
    )

    private fun requirements(allowedSurfaces: Set<SurfaceMode>) = PlaybackRequirements(
        profile = SessionProfile.GUIDE,
        priority = SessionPriority.STARTUP_SPEED,
        qualityIntent = VideoQualityIntent.PREVIEW,
        displayModeSwitchAllowed = false,
        frameRatePreference = FrameRatePreference.OFF,
        hdrPreference = HdrPreference.AUTO,
        decoderPreference = DecoderPreference.AUTO,
        softwareDecodeFallbackAllowed = false,
        subtitleFidelity = SubtitleFidelity.COMPATIBLE,
        subtitlesEnabled = false,
        audioOutput = AudioOutputPreference.AUTO,
        pcmProcessingAllowed = true,
        buffering = BufferingPreference.RECOMMENDED,
        gpuRenderingAllowed = false,
        eligibleEngines = setOf(EngineType.MEDIA3),
        allowedSurfaceModes = allowedSurfaces,
        secureOutputRequired = false,
        resourceBudget = ResourceBudget(),
    )

    private fun failure(retryability: Retryability) = PlaybackFailure(
        code = FailureCode.VIDEO_DECODER_FAILED,
        domain = FailureDomain.VIDEO_DECODER,
        phase = FailurePhase.ENGINE_START,
        retryability = retryability,
    )
}
