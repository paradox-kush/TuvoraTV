package com.nuvio.tv.playback.android.output

import com.nuvio.tv.playback.core.ActiveWorkReleaseReason
import com.nuvio.tv.playback.core.AudioOutputPreference
import com.nuvio.tv.playback.core.BufferingPreference
import com.nuvio.tv.playback.core.DecoderPreference
import com.nuvio.tv.playback.core.EngineType
import com.nuvio.tv.playback.core.FrameRatePreference
import com.nuvio.tv.playback.core.HdrPreference
import com.nuvio.tv.playback.core.PlaybackOutputRequest
import com.nuvio.tv.playback.core.PlaybackOutputStatus
import com.nuvio.tv.playback.core.PlaybackRequirements
import com.nuvio.tv.playback.core.PlaybackResult
import com.nuvio.tv.playback.core.ResourceBudget
import com.nuvio.tv.playback.core.SessionPriority
import com.nuvio.tv.playback.core.SessionProfile
import com.nuvio.tv.playback.core.SubtitleFidelity
import com.nuvio.tv.playback.core.SurfaceMode
import com.nuvio.tv.playback.core.VideoOutputFacts
import com.nuvio.tv.playback.core.VideoQualityIntent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidPlaybackOutputControllerTest {
    @Test
    fun `apply reports policy and factual waiting states without touching Window`() = runTest {
        val host = FakeHost()
        val controller = controller(host)

        assertEquals(PlaybackOutputStatus.DISABLED, controller.apply(request(1, enabled = false)).status())
        assertEquals(PlaybackOutputStatus.WAITING_FOR_COMMIT, controller.apply(request(2, committed = false)).status())
        assertEquals(PlaybackOutputStatus.WAITING_FOR_FRAME_RATE, controller.apply(request(3, frameRate = null)).status())
        assertEquals(PlaybackOutputStatus.WAITING_FOR_FRAME_RATE, controller.apply(request(3, frameRate = 9f)).status())
        assertEquals(emptyList<Int>(), host.requests)
    }

    @Test
    fun `resolution only waits for video size then switches without inventing a frame rate`() = runTest {
        val host = FakeHost(
            modes = listOf(
                AndroidDisplayMode(1, 1920, 1080, 60f),
                AndroidDisplayMode(4, 3840, 2160, 60f),
            ),
        )
        val controller = controller(host)
        assertEquals(
            PlaybackOutputStatus.WAITING_FOR_VIDEO_SIZE,
            controller.apply(
                request(
                    generation = 1,
                    frameRate = null,
                    preference = FrameRatePreference.OFF,
                    resolutionMatching = true,
                ),
            ).status(),
        )
        assertEquals(
            PlaybackOutputStatus.APPLIED,
            controller.apply(
                request(
                    generation = 1,
                    frameRate = null,
                    preference = FrameRatePreference.OFF,
                    resolutionMatching = true,
                    dimensions = com.nuvio.tv.playback.core.VideoDimensions(3840, 2160),
                ),
            ).status(),
        )
        assertEquals(listOf(4), host.requests)
    }

    @Test
    fun `on start resolution matching waits to make one factual switch`() = runTest {
        val controller = controller(FakeHost())
        assertEquals(
            PlaybackOutputStatus.WAITING_FOR_VIDEO_SIZE,
            controller.apply(request(1, resolutionMatching = true)).status(),
        )
    }

    @Test
    fun `unsupported display and invalid mode topology are typed outcomes`() = runTest {
        val missing = FakeHost(snapshotAvailable = false)
        assertEquals(PlaybackOutputStatus.UNSUPPORTED, controller(missing).apply(request(1)).status())

        val invalid = FakeHost(modes = listOf(AndroidDisplayMode(0, 1920, 1080, 24f)))
        assertEquals(
            PlaybackOutputStatus.NO_COMPATIBLE_MODE,
            controller(invalid).apply(request(1)).status(),
        )
    }

    @Test
    fun `applied requires stable observed mode and a timeout is not claimed as success`() = runTest {
        val switching = FakeHost()
        assertEquals(PlaybackOutputStatus.APPLIED, controller(switching).apply(request(1)).status())
        assertEquals(listOf(2), switching.requests)

        val stuck = FakeHost(applyRequests = false)
        assertEquals(PlaybackOutputStatus.APPLY_NOT_CONFIRMED, controller(stuck).apply(request(1)).status())
        assertEquals(listOf(2), stuck.requests)
    }

    @Test
    fun `equal fact revision deduplicates while a newer revision may retry`() = runTest {
        val host = FakeHost(applyRequests = false)
        val controller = controller(host)
        assertEquals(PlaybackOutputStatus.APPLY_NOT_CONFIRMED, controller.apply(request(1)).status())
        assertEquals(PlaybackOutputStatus.APPLY_NOT_CONFIRMED, controller.apply(request(1)).status())
        assertEquals(listOf(2), host.requests)
        assertEquals(
            PlaybackOutputStatus.APPLY_NOT_CONFIRMED,
            controller.apply(request(1, factsRevision = 1)).status(),
        )
        assertEquals(listOf(2, 2), host.requests)
    }

    @Test
    fun `on start applies once while on rate change may select a new factual cadence`() = runTest {
        val onStartHost = FakeHost()
        val onStart = controller(onStartHost)
        assertEquals(PlaybackOutputStatus.APPLIED, onStart.apply(request(1, frameRate = 24f)).status())
        assertEquals(
            PlaybackOutputStatus.ALREADY_EFFECTIVE,
            onStart.apply(request(1, frameRate = 30f)).status(),
        )
        assertEquals(listOf(2), onStartHost.requests)

        val rateChangeHost = FakeHost()
        val rateChange = controller(rateChangeHost)
        assertEquals(
            PlaybackOutputStatus.APPLIED,
            rateChange.apply(request(1, frameRate = 24f, preference = FrameRatePreference.ON_RATE_CHANGE)).status(),
        )
        assertEquals(
            PlaybackOutputStatus.APPLIED,
            rateChange.apply(request(1, frameRate = 30f, preference = FrameRatePreference.ON_RATE_CHANGE)).status(),
        )
        assertEquals(listOf(2, 3), rateChangeHost.requests)
    }

    @Test
    fun `internal release reasons preserve mode and terminal reasons restore original`() = runTest {
        val preserveReasons = listOf(
            ActiveWorkReleaseReason.REBUILD,
            ActiveWorkReleaseReason.RESELECT,
            ActiveWorkReleaseReason.HANDOFF,
            ActiveWorkReleaseReason.SURFACE_LOST,
        )
        preserveReasons.forEach { reason ->
            val host = FakeHost()
            val controller = controller(host)
            controller.apply(request(7))
            controller.reset(7, reason)
            assertEquals("$reason must preserve", 2, host.currentModeId)
        }

        val terminalReasons = listOf(
            ActiveWorkReleaseReason.STOP,
            ActiveWorkReleaseReason.REPLACE_REQUEST,
            ActiveWorkReleaseReason.COMPLETED,
            ActiveWorkReleaseReason.FAILURE,
            ActiveWorkReleaseReason.LIFECYCLE_INACTIVE,
        )
        terminalReasons.forEach { reason ->
            val host = FakeHost()
            val controller = controller(host)
            controller.apply(request(7))
            controller.reset(7, reason)
            assertEquals("$reason must restore", 1, host.currentModeId)
            controller.reset(7, reason)
            assertEquals(listOf(2, 1), host.requests)
        }
    }

    @Test
    fun `null terminal generation restores while stale generation cannot clobber current owner`() = runTest {
        val host = FakeHost()
        val controller = controller(host)
        controller.apply(request(8))
        controller.reset(7, ActiveWorkReleaseReason.STOP)
        assertEquals(2, host.currentModeId)
        controller.reset(null, ActiveWorkReleaseReason.STOP)
        assertEquals(1, host.currentModeId)
    }

    @Test
    fun `admitted handoff generation inherits output ownership before new facts arrive`() = runTest {
        val host = FakeHost()
        val controller = controller(host)
        controller.apply(request(1))
        controller.reset(1, ActiveWorkReleaseReason.HANDOFF)
        assertEquals(
            PlaybackOutputStatus.WAITING_FOR_FRAME_RATE,
            controller.apply(
                request(
                    generation = 2,
                    frameRate = null,
                    preference = FrameRatePreference.ON_RATE_CHANGE,
                ),
            ).status(),
        )
        controller.reset(1, ActiveWorkReleaseReason.STOP)
        assertEquals(2, host.currentModeId)
        controller.reset(2, ActiveWorkReleaseReason.STOP)
        assertEquals(1, host.currentModeId)
    }

    @Test
    fun `guide or preference off restores original mode`() = runTest {
        val host = FakeHost()
        val controller = controller(host)
        controller.apply(request(1))
        assertEquals(
            PlaybackOutputStatus.DISABLED,
            controller.apply(request(2, enabled = false)).status(),
        )
        assertEquals(listOf(2, 1), host.requests)
    }

    @Test
    fun `actual Window operation failure is a nonfatal typed output status`() = runTest {
        val result = controller(FakeHost(failRequests = true)).apply(request(1))
        assertEquals(PlaybackOutputStatus.APPLY_FAILED, result.status())
        assertEquals(
            PlaybackOutputStatus.APPLY_FAILED,
            controller(FakeHost(failSnapshots = true)).apply(request(1)).status(),
        )
    }

    @Test
    fun `lower fact revision cannot apply after a newer revision`() = runTest {
        val host = FakeHost()
        val controller = controller(host)
        controller.apply(request(1, frameRate = 30f, preference = FrameRatePreference.ON_RATE_CHANGE, factsRevision = 2))
        assertEquals(
            PlaybackOutputStatus.NOT_REQUESTED,
            controller.apply(
                request(1, frameRate = 24f, preference = FrameRatePreference.ON_RATE_CHANGE, factsRevision = 1),
            ).status(),
        )
        assertEquals(listOf(3), host.requests)
    }

    private fun TestScope.controller(host: FakeHost) = AndroidPlaybackOutputController(
        host = host,
        mainDispatcher = StandardTestDispatcher(testScheduler),
        verification = DisplayModeVerificationPolicy(
            pollIntervalMs = 1,
            maximumPolls = 2,
            stablePollsRequired = 2,
        ),
    )

    private fun request(
        generation: Long,
        enabled: Boolean = true,
        committed: Boolean = true,
        frameRate: Float? = 24f,
        preference: FrameRatePreference = FrameRatePreference.ON_START,
        resolutionMatching: Boolean = false,
        dimensions: com.nuvio.tv.playback.core.VideoDimensions? = null,
        factsRevision: Long = 0,
    ) = PlaybackOutputRequest(
        generation = generation,
        requirements = requirements(enabled, preference, resolutionMatching),
        facts = VideoOutputFacts(revision = factsRevision, frameRate = frameRate, dimensions = dimensions),
        committed = committed,
    )

    private fun requirements(
        enabled: Boolean,
        preference: FrameRatePreference,
        resolutionMatching: Boolean,
    ) = PlaybackRequirements(
        profile = SessionProfile.FULLSCREEN,
        priority = SessionPriority.QUALITY_AND_STABILITY,
        qualityIntent = VideoQualityIntent.FULL,
        displayModeSwitchAllowed = enabled,
        resolutionMatchingEnabled = resolutionMatching,
        frameRatePreference = preference,
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
        allowedSurfaceModes = setOf(SurfaceMode.SURFACE_VIEW),
        secureOutputRequired = false,
        resourceBudget = ResourceBudget(),
    )

    private fun PlaybackResult<com.nuvio.tv.playback.core.PlaybackOutputApplication>.status() =
        (this as PlaybackResult.Success).value.status

    private class FakeHost(
        private val snapshotAvailable: Boolean = true,
        private val modes: List<AndroidDisplayMode> = listOf(
            AndroidDisplayMode(1, 1920, 1080, 60f),
            AndroidDisplayMode(2, 1920, 1080, 24f),
            AndroidDisplayMode(3, 1920, 1080, 30f),
        ),
        private val applyRequests: Boolean = true,
        private val failRequests: Boolean = false,
        private val failSnapshots: Boolean = false,
    ) : AndroidDisplayModeHost {
        var currentModeId: Int = 1
        val requests = mutableListOf<Int>()

        override fun snapshot(): AndroidDisplayModeSnapshot? {
            if (failSnapshots) error("synthetic snapshot failure")
            return if (snapshotAvailable) {
                AndroidDisplayModeSnapshot(currentModeId, modes)
            } else {
                null
            }
        }

        override fun requestMode(modeId: Int) {
            if (failRequests) error("synthetic host failure")
            requests += modeId
            if (applyRequests) currentModeId = modeId
        }
    }
}
