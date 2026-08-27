package com.nuvio.tv.playback.lab

import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.data.local.LiveChannelRef
import com.nuvio.tv.playback.core.AudioOutputPreference
import com.nuvio.tv.playback.core.BufferingPreference
import com.nuvio.tv.playback.core.DecoderPreference
import com.nuvio.tv.playback.core.DnsPolicy
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanMedia3PlaybackLabPolicyTest {
    @Test
    fun `catalog selection is deterministic across storage ordering`() {
        val one = account(id = "account-one")
        val two = account(id = "account-two")
        val recents = listOf(
            recent(one, 19, playedAt = 100),
            recent(two, 73, playedAt = 200),
            recent(one, 27, playedAt = 200),
        )

        val forward = buildDebugFixtureCatalog(listOf(one, two), recents) as DebugFixtureCatalog.Ready
        val reversed = buildDebugFixtureCatalog(listOf(two, one), recents.reversed()) as DebugFixtureCatalog.Ready

        assertEquals(forward.options.map { it.fingerprint }, reversed.options.map { it.fingerprint })
        assertEquals(forward.options.map { it.displayLabel }, reversed.options.map { it.displayLabel })
    }

    @Test
    fun `catalog labels and object rendering never expose transport or account secrets`() {
        val secret = account(
            id = "https://private-user:private-password@secret.example:8443",
            baseUrl = "https://secret.example:8443",
            username = "private-user",
        )
        val recent = recent(
            secret,
            73,
            playedAt = 200,
            name = "https://private-user:private-password@secret.example/live?token=abc",
        )

        val ready = buildDebugFixtureCatalog(listOf(secret), listOf(recent)) as DebugFixtureCatalog.Ready
        val rendered = ready.options.single().let { "${it.displayLabel} ${it.fixture}" }

        listOf("secret.example", "private-user", "private-password", "token=abc", recent.id, recent.streamUrl)
            .forEach { secretValue -> assertFalse(rendered.contains(secretValue)) }
        assertTrue(rendered.contains("Live channel"))
    }

    @Test
    fun `same request and profile feed both engines while libmpv visibly falls back from DoH`() {
        val account = account(id = "doh-account", dnsProvider = XtreamAccount.DNS_CLOUDFLARE)
        val fixture = (buildDebugFixtureCatalog(
            listOf(account),
            listOf(recent(account, 3, playedAt = 10)),
        ) as DebugFixtureCatalog.Ready).options.single().fixture
        val intent = fixture.playbackIntent(DnsPolicy.SHARED_APPLICATION_RESOLVER)

        assertSame(intent.requestFor(EngineType.MEDIA3), intent.requestFor(EngineType.LIBMPV))
        assertEquals(SessionProfile.GUIDE, intent.profile)
        assertEquals(DnsPolicy.SHARED_APPLICATION_RESOLVER, intent.request.dnsPolicy)
        assertEquals(LabEligibilityReason.ELIGIBLE, successfulLabEligibilityReason(intent, EngineType.MEDIA3))
        assertEquals(
            LabEligibilityReason.SYSTEM_DNS_FALLBACK,
            successfulLabEligibilityReason(intent, EngineType.LIBMPV),
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
        dnsProvider: String = XtreamAccount.DNS_SYSTEM,
    ) = XtreamAccount(
        id = id,
        name = "fixture",
        baseUrl = baseUrl,
        username = username,
        password = "private-password",
        sourceType = sourceType,
        dnsProvider = dnsProvider,
    )

    private fun recent(
        account: XtreamAccount,
        streamId: Int,
        playedAt: Long,
        name: String = "Safe News HD",
    ) = LiveChannelRef(
        id = XtreamItemRegistry.liveId(account.id, streamId),
        name = name,
        logo = null,
        streamUrl = "https://do-not-log.example/$streamId",
        playedAt = playedAt,
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
