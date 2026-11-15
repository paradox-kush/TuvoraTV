package com.nuvio.tv.playback.wiring

import com.nuvio.tv.playback.core.ActiveWorkReleaseReason
import com.nuvio.tv.playback.core.AudioFocusPort
import com.nuvio.tv.playback.core.AudioOutputPreference
import com.nuvio.tv.playback.core.EnginePreference
import com.nuvio.tv.playback.core.EngineType
import com.nuvio.tv.playback.core.FrameRatePreference
import com.nuvio.tv.playback.core.NoopAudioFocusPort
import com.nuvio.tv.playback.core.PlaybackOutputStatus
import com.nuvio.tv.playback.core.PlaybackPreferences
import com.nuvio.tv.playback.core.PlaybackResult
import com.nuvio.tv.playback.core.SurfaceMode
import com.nuvio.tv.playback.core.TileHostConfig
import com.nuvio.tv.playback.core.VideoDimensions
import com.nuvio.tv.playback.core.VideoPreference
import com.nuvio.tv.playback.settings.CleanPlaybackPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TileHostCompositionTest {
    private val tile = TileHostConfig(bufferBytes = 6 * 1024 * 1024)
    private val plan = TileHostComposition.plan(tile)

    @Test
    fun `tile mode turns off focus session display switching tunnelling passthrough and offload`() {
        assertFalse("audio focus", plan.requestsAudioFocus)
        assertFalse("media session", plan.createsMediaSession)
        assertFalse("display mode / frame rate", plan.switchesDisplayMode)
        assertTrue("tunnelling forced off", plan.forcesTunnellingOff)
        assertTrue("passthrough forced off", plan.forcesPassthroughOff)
        assertTrue("offload forced off", plan.forcesAudioOffloadOff)
        assertTrue("audio is decoded only for the audible tile", plan.startsAudioDisabled)
        assertFalse("text tracks", plan.textTracksEnabled)
    }

    @Test
    fun `tile mode is media3 only on view surfaces with its own buffer and a 720p cap`() {
        assertEquals("no mpv engine, so no mpv fallback", setOf(EngineType.MEDIA3), plan.enginesAllowed)
        assertEquals(setOf(SurfaceMode.SURFACE_VIEW, SurfaceMode.TEXTURE_VIEW), plan.surfaceModes)
        assertEquals(6 * 1024 * 1024, plan.targetBufferBytes)
        assertEquals(VideoDimensions(1280, 720), plan.maxAdaptiveVideo)
    }

    @Test
    fun `the host without a tile config is exactly the unrestricted production host`() {
        val none = TileHostComposition.plan(null)
        assertEquals(TileHostPlan.UNRESTRICTED, none)
        assertTrue(none.requestsAudioFocus)
        assertTrue(none.createsMediaSession)
        assertTrue(none.switchesDisplayMode)
        assertFalse(none.forcesTunnellingOff)
        assertFalse(none.forcesPassthroughOff)
        assertFalse(none.forcesAudioOffloadOff)
        assertFalse(none.startsAudioDisabled)
        assertEquals(EngineType.entries.toSet(), none.enginesAllowed)
        assertEquals(null, none.surfaceModes)
        assertEquals(null, none.targetBufferBytes)
        assertEquals(null, none.maxAdaptiveVideo)
        assertTrue(none.textTracksEnabled)
    }

    @Test
    fun `unrestricted preferences pass through untouched`() {
        val stored = CleanPlaybackPreferences(
            playback = PlaybackPreferences(
                engine = EnginePreference.LIBMPV,
                audio = PlaybackPreferences().audio.copy(output = AudioOutputPreference.PASSTHROUGH),
            ),
        )
        assertSame(stored, TileHostComposition.constrain(stored, TileHostPlan.UNRESTRICTED))
    }

    @Test
    fun `a tile narrows the viewer's preferences to what a tile can honour`() {
        val stored = CleanPlaybackPreferences(
            playback = PlaybackPreferences(
                engine = EnginePreference.LIBMPV,
                automaticFallback = true,
                audio = PlaybackPreferences().audio.copy(
                    output = AudioOutputPreference.PASSTHROUGH,
                    downmixToStereo = true,
                    normalization = true,
                    skipSilence = true,
                    delayMs = 250,
                ),
                display = PlaybackPreferences().display.copy(
                    frameRate = FrameRatePreference.ON_RATE_CHANGE,
                    resolutionMatching = true,
                ),
            ),
        )
        val tiled = TileHostComposition.constrain(stored, plan).playback

        assertEquals("media3 pinned", EnginePreference.MEDIA3, tiled.engine)
        assertFalse("no engine fallback", tiled.automaticFallback)
        assertEquals("pcm, so mute acts on decoded audio", AudioOutputPreference.PCM, tiled.audio.output)
        assertFalse(tiled.audio.downmixToStereo)
        assertFalse(tiled.audio.normalization)
        assertFalse(tiled.audio.skipSilence)
        assertEquals(0L, tiled.audio.delayMs)
        assertFalse("text tracks", tiled.subtitles.enabled)
        assertEquals("no frame-rate matching", FrameRatePreference.OFF, tiled.display.frameRate)
        assertFalse("no resolution matching", tiled.display.resolutionMatching)
        assertEquals("720p cap", VideoDimensions(1280, 720), tiled.video.maximumDimensions)
    }

    @Test
    fun `a stricter cap the viewer already chose is kept`() {
        val stored = CleanPlaybackPreferences(
            playback = PlaybackPreferences(video = VideoPreference(maximumDimensions = VideoDimensions(854, 480))),
        )
        assertEquals(
            VideoDimensions(854, 480),
            TileHostComposition.constrain(stored, plan).playback.video.maximumDimensions,
        )
    }

    @Test
    fun `tile audio focus is the no-op port and the real one is never built`() {
        var built = 0
        val real = object : AudioFocusPort by NoopAudioFocusPort {}
        assertSame(NoopAudioFocusPort, TileHostComposition.audioFocus(plan) { built++; real })
        assertEquals("the real port is not even constructed for a tile", 0, built)
        assertSame(real, TileHostComposition.audioFocus(TileHostPlan.UNRESTRICTED) { built++; real })
        assertEquals(1, built)
    }

    @Test
    fun `tile output controller never touches the display and always succeeds`() = runTest {
        val applied = NoopPlaybackOutputController.apply(
            com.nuvio.tv.playback.core.PlaybackOutputRequest(
                generation = 1,
                requirements = com.nuvio.tv.playback.core.PlaybackRequirements(
                    profile = com.nuvio.tv.playback.core.SessionProfile.GUIDE,
                    priority = com.nuvio.tv.playback.core.SessionPriority.STARTUP_SPEED,
                    qualityIntent = com.nuvio.tv.playback.core.VideoQualityIntent.PREVIEW,
                    displayModeSwitchAllowed = false,
                    frameRatePreference = FrameRatePreference.OFF,
                    hdrPreference = com.nuvio.tv.playback.core.HdrPreference.AUTO,
                    decoderPreference = com.nuvio.tv.playback.core.DecoderPreference.AUTO,
                    softwareDecodeFallbackAllowed = false,
                    subtitleFidelity = com.nuvio.tv.playback.core.SubtitleFidelity.COMPATIBLE,
                    subtitlesEnabled = false,
                    audioOutput = AudioOutputPreference.PCM,
                    pcmProcessingAllowed = true,
                    buffering = com.nuvio.tv.playback.core.BufferingPreference.LOW_LATENCY_LIVE,
                    gpuRenderingAllowed = false,
                    eligibleEngines = setOf(EngineType.MEDIA3),
                    secureOutputRequired = false,
                    resourceBudget = com.nuvio.tv.playback.core.ResourceBudget(),
                ),
                facts = com.nuvio.tv.playback.core.VideoOutputFacts(),
                committed = true,
            ),
        )
        assertEquals(
            PlaybackOutputStatus.DISABLED,
            (applied as PlaybackResult.Success).value.status,
        )
        assertTrue(NoopPlaybackOutputController.reset(1, ActiveWorkReleaseReason.entries.first()) is PlaybackResult.Success)
    }

    @Test
    fun `tile config rejects a zero buffer`() {
        val failed = runCatching { TileHostConfig(bufferBytes = 0) }.isFailure
        assertTrue("buffer must be positive", failed)
    }
}
