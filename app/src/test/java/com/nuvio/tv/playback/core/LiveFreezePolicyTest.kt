package com.nuvio.tv.playback.core

import com.nuvio.tv.playback.core.LiveFreezePolicy.Action
import com.nuvio.tv.playback.core.LiveFreezePolicy.FreezeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveFreezePolicyTest {
    private val windowMs = 5_000L

    private fun metrics(video: Long?, audio: Long?, generation: Long = 1) = PlaybackEngineMetricsSnapshot(
        generation = generation,
        videoFramesRendered = video,
        videoFramesSkipped = null,
        videoFramesDropped = null,
        audioBuffersRendered = audio,
        audioBuffersSkipped = null,
        audioBuffersDropped = null,
    )

    @Test
    fun `flat video with advancing audio is a video only freeze`() {
        assertEquals(
            FreezeKind.VIDEO_ONLY,
            LiveFreezePolicy.classify(metrics(100, 10), metrics(100, 300), windowMs),
        )
    }

    @Test
    fun `flat video and flat audio is a source freeze`() {
        assertEquals(
            FreezeKind.SOURCE,
            LiveFreezePolicy.classify(metrics(100, 10), metrics(100, 10), windowMs),
        )
    }

    @Test
    fun `unknown audio counts as a source freeze`() {
        assertEquals(
            FreezeKind.SOURCE,
            LiveFreezePolicy.classify(metrics(100, null), metrics(100, null), windowMs),
        )
    }

    @Test
    fun `a trickle below one frame per second is still a freeze`() {
        assertEquals(
            "4 frames in 5 s is not moving video",
            FreezeKind.VIDEO_ONLY,
            LiveFreezePolicy.classify(metrics(100, 10), metrics(104, 300), windowMs),
        )
        assertNull(
            "5 frames in 5 s is one per second and counts as moving",
            LiveFreezePolicy.classify(metrics(100, 10), metrics(105, 300), windowMs),
        )
    }

    @Test
    fun `moving video is not a freeze`() {
        assertNull(LiveFreezePolicy.classify(metrics(100, 10), metrics(225, 300), windowMs))
    }

    @Test
    fun `missing video metrics are not a freeze`() {
        assertNull(LiveFreezePolicy.classify(metrics(null, 10), metrics(null, 300), windowMs))
        assertNull(LiveFreezePolicy.classify(metrics(1, 10, generation = 1), metrics(1, 10, generation = 2), windowMs))
    }

    @Test
    fun `a source freeze always reconnects`() {
        assertEquals(
            Action.RECONNECT,
            LiveFreezePolicy.decide(FreezeKind.SOURCE, EngineType.MEDIA3, handoffAvailable = true, priorVideoOnlyFreezes = 3, inProbation = true),
        )
    }

    @Test
    fun `the first video only freeze reconnects once`() {
        assertEquals(
            Action.RECONNECT,
            LiveFreezePolicy.decide(FreezeKind.VIDEO_ONLY, EngineType.MEDIA3, handoffAvailable = true, priorVideoOnlyFreezes = 0, inProbation = false),
        )
    }

    @Test
    fun `the second video only freeze hands off`() {
        assertEquals(
            Action.HANDOFF,
            LiveFreezePolicy.decide(FreezeKind.VIDEO_ONLY, EngineType.MEDIA3, handoffAvailable = true, priorVideoOnlyFreezes = 1, inProbation = false),
        )
    }

    @Test
    fun `a video only freeze during probation hands off`() {
        assertEquals(
            Action.HANDOFF,
            LiveFreezePolicy.decide(FreezeKind.VIDEO_ONLY, EngineType.MEDIA3, handoffAvailable = true, priorVideoOnlyFreezes = 0, inProbation = true),
        )
    }

    @Test
    fun `without a handoff target a video only freeze keeps reconnecting`() {
        assertEquals(
            Action.RECONNECT,
            LiveFreezePolicy.decide(FreezeKind.VIDEO_ONLY, EngineType.MEDIA3, handoffAvailable = false, priorVideoOnlyFreezes = 2, inProbation = true),
        )
    }

    @Test
    fun `a video only freeze on libmpv keeps playing because audio is alive`() {
        assertEquals(
            Action.KEEP_PLAYING,
            LiveFreezePolicy.decide(FreezeKind.VIDEO_ONLY, EngineType.LIBMPV, handoffAvailable = false, priorVideoOnlyFreezes = 0, inProbation = false),
        )
        assertEquals(
            Action.RECONNECT,
            LiveFreezePolicy.decide(FreezeKind.SOURCE, EngineType.LIBMPV, handoffAvailable = false, priorVideoOnlyFreezes = 0, inProbation = false),
        )
    }

    @Test
    fun `probation lasts the stable playback time in whole windows`() {
        assertEquals(6, LiveFreezePolicy.probationWindows(stableMs = 30_000, windowMs = 5_000))
        assertEquals(7, LiveFreezePolicy.probationWindows(stableMs = 31_000, windowMs = 5_000))
        assertEquals(1, LiveFreezePolicy.probationWindows(stableMs = 1, windowMs = 5_000))
    }
}
