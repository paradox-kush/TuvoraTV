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
    fun `a known 25 fps stream must render a fifth of its frames`() {
        assertEquals(25L, LiveFreezePolicy.requiredFrames(windowMs, 25f))
        assertEquals(
            FreezeKind.VIDEO_ONLY,
            LiveFreezePolicy.classify(metrics(100, 10), metrics(101, 300), windowMs, frameRate = 25f),
        )
        assertEquals(
            FreezeKind.VIDEO_ONLY,
            LiveFreezePolicy.classify(metrics(100, 10), metrics(124, 300), windowMs, frameRate = 25f),
        )
        assertNull(LiveFreezePolicy.classify(metrics(100, 10), metrics(125, 300), windowMs, frameRate = 25f))
    }

    @Test
    fun `an unknown or slideshow frame rate only needs one new frame`() {
        assertEquals(1L, LiveFreezePolicy.requiredFrames(windowMs, null))
        assertEquals(1L, LiveFreezePolicy.requiredFrames(windowMs, 4.9f))
        assertNull(LiveFreezePolicy.classify(metrics(100, 10), metrics(101, 300), windowMs))
        assertNull(LiveFreezePolicy.classify(metrics(100, 10), metrics(101, 300), windowMs, frameRate = 1f))
        assertEquals(FreezeKind.VIDEO_ONLY, LiveFreezePolicy.classify(metrics(100, 10), metrics(100, 300), windowMs))
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
    fun `the first source freeze reconnects`() {
        assertEquals(
            Action.RECONNECT,
            LiveFreezePolicy.decide(FreezeKind.SOURCE, EngineType.MEDIA3, handoffAvailable = true, priorFreezes = 0, inProbation = false),
        )
    }

    @Test
    fun `a second source freeze on media3 hands off when libmpv is eligible`() {
        assertEquals(
            Action.HANDOFF,
            LiveFreezePolicy.decide(FreezeKind.SOURCE, EngineType.MEDIA3, handoffAvailable = true, priorFreezes = 1, inProbation = true),
        )
        assertEquals(
            Action.RECONNECT,
            LiveFreezePolicy.decide(FreezeKind.SOURCE, EngineType.MEDIA3, handoffAvailable = false, priorFreezes = 1, inProbation = true),
        )
        assertEquals(
            "libmpv has nowhere to hand off",
            Action.RECONNECT,
            LiveFreezePolicy.decide(FreezeKind.SOURCE, EngineType.LIBMPV, handoffAvailable = false, priorFreezes = 5, inProbation = true),
        )
    }

    @Test
    fun `six freezes of a channel in ten minutes use the budget up`() {
        val now = 1_000_000L
        assertEquals(false, LiveFreezeBudgetPolicy.exhausted(List(5) { now - it * 60_000L }, now))
        assertEquals(true, LiveFreezeBudgetPolicy.exhausted(List(6) { now - it * 60_000L }, now))
        assertEquals("the oldest fell out of the window", false, LiveFreezeBudgetPolicy.exhausted(List(6) { now - it * 130_000L }, now))
        val kept = mutableListOf(now - 700_000L, now - 100L)
        LiveFreezeBudgetPolicy.prune(kept, now)
        assertEquals(listOf(now - 100L), kept)
    }

    @Test
    fun `the first video only freeze reconnects once`() {
        assertEquals(
            Action.RECONNECT,
            LiveFreezePolicy.decide(FreezeKind.VIDEO_ONLY, EngineType.MEDIA3, handoffAvailable = true, priorFreezes = 0, inProbation = false),
        )
    }

    @Test
    fun `the second video only freeze hands off`() {
        assertEquals(
            Action.HANDOFF,
            LiveFreezePolicy.decide(FreezeKind.VIDEO_ONLY, EngineType.MEDIA3, handoffAvailable = true, priorFreezes = 1, inProbation = false),
        )
    }

    @Test
    fun `a video only freeze during probation hands off`() {
        assertEquals(
            Action.HANDOFF,
            LiveFreezePolicy.decide(FreezeKind.VIDEO_ONLY, EngineType.MEDIA3, handoffAvailable = true, priorFreezes = 0, inProbation = true),
        )
    }

    @Test
    fun `without a handoff target a video only freeze keeps reconnecting`() {
        assertEquals(
            Action.RECONNECT,
            LiveFreezePolicy.decide(FreezeKind.VIDEO_ONLY, EngineType.MEDIA3, handoffAvailable = false, priorFreezes = 2, inProbation = true),
        )
    }

    @Test
    fun `a video only freeze on libmpv keeps playing because audio is alive`() {
        assertEquals(
            Action.KEEP_PLAYING,
            LiveFreezePolicy.decide(FreezeKind.VIDEO_ONLY, EngineType.LIBMPV, handoffAvailable = false, priorFreezes = 0, inProbation = false),
        )
        assertEquals(
            Action.RECONNECT,
            LiveFreezePolicy.decide(FreezeKind.SOURCE, EngineType.LIBMPV, handoffAvailable = false, priorFreezes = 0, inProbation = false),
        )
    }

    @Test
    fun `probation lasts the stable playback time in whole windows`() {
        assertEquals(6, LiveFreezePolicy.probationWindows(stableMs = 30_000, windowMs = 5_000))
        assertEquals(7, LiveFreezePolicy.probationWindows(stableMs = 31_000, windowMs = 5_000))
        assertEquals(1, LiveFreezePolicy.probationWindows(stableMs = 1, windowMs = 5_000))
    }
}
