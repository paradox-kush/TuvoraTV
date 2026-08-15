package com.nuvio.tv.core.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reporter is what turns policy decisions into the one `live_playback_freeze` event, so
 * these tests drive it with explicit clocks and assert on the emitted properties — the
 * field-data bug (video_stalled frozen_ms averaging 16ms against a 6s detection threshold)
 * lived in that assembly.
 */
class LivePlaybackFreezeReporterTest {

    private val events = mutableListOf<Pair<String, Map<String, Any>>>()
    private val reporter = LivePlaybackFreezeReporter { name, properties ->
        events += name to properties
    }

    private fun profile() = LivePlaybackFreezeReporter.Profile(
        engine = "mpv",
        bufferEngineEnabled = false,
        minBufferMs = 50_000,
        maxBufferMs = 50_000,
        bufferForPlaybackMs = 1_000,
        bufferForPlaybackAfterRebufferMs = 2_000,
        streamContainer = "ts",
        iptvKind = "xtream",
    )

    /**
     * Plays healthily until [videoStallAtMs]: the playhead, buffered edge and frame counter all
     * advance on every sample. After that the frame counter pins while audio keeps the playhead
     * moving — the reported "picture froze, audio kept going" shape.
     */
    private fun playHealthyThenStallVideo(videoStallAtMs: Long) {
        reporter.onLivePlaybackStarted(
            profile = profile(),
            nowMs = 0L,
            positionMs = 0L,
            videoProgressTicks = 100L,
        )
        var ticks = 100L
        var t = 0L
        while (t < videoStallAtMs) {
            t += 10_000L
            ticks += 300L
            sample(nowMs = t, ticks = ticks)
        }
    }

    private fun sample(
        nowMs: Long,
        ticks: Long,
    ): LivePlaybackFreezePolicy.Decision = reporter.onSample(
        nowMs = nowMs,
        positionMs = nowMs,
        bufferedPositionMs = nowMs + 5_000L,
        state = LivePlaybackFreezePolicy.PlaybackState.READY,
        wantsToPlay = true,
        rebufferCount = 0,
        rebufferTotalMs = 0L,
        videoProgressTicks = ticks,
        hasVideoTrack = true,
    )

    @Test
    fun `a video freeze reports frozen time from the video stall start`() {
        // Last frame at t=20s; audio keeps the playhead advancing afterwards.
        playHealthyThenStallVideo(videoStallAtMs = 20_000L)
        sample(nowMs = 24_000L, ticks = 700L)

        // Detection threshold crossed 6.5s after the last frame.
        val start = sample(nowMs = 26_500L, ticks = 700L)
        assertEquals(
            LivePlaybackFreezePolicy.Decision.Start(LivePlaybackFreezePolicy.Kind.VIDEO_STALLED),
            start,
        )

        // Frames come back one second later.
        val recover = sample(nowMs = 27_500L, ticks = 730L)
        assertEquals(LivePlaybackFreezePolicy.Decision.Recover, recover)

        assertEquals(1, events.size)
        val properties = events.single().second
        // The viewer stared at a frozen picture from t=20s until t=27.5s. Basing this at the
        // detection tick instead reported 1s — and in the field, sub-threshold impossibilities
        // like 16ms — which is what made the first fleet numbers unusable.
        assertEquals("frozen_ms must count from the video stall start", 7_500L, properties["frozen_ms"])
        assertEquals(
            "played_ms_before_freeze ends when the picture died, not when detection fired",
            20_000L,
            properties["played_ms_before_freeze"],
        )
        assertEquals("video_stalled", properties["freeze_kind"])
        assertEquals(true, properties["recovered"])
    }

    @Test
    fun `a video freeze flushed unrecovered still counts from the video stall start`() {
        playHealthyThenStallVideo(videoStallAtMs = 20_000L)
        sample(nowMs = 26_500L, ticks = 700L)
        assertTrue(reporter.isFreezeOpen)

        // The viewer gives up and leaves the channel at t=30s with the picture still dead.
        reporter.onLivePlaybackStopped(
            nowMs = 30_000L,
            positionMs = 30_000L,
            bufferedPositionMs = 32_000L,
            rebufferCount = 0,
            rebufferTotalMs = 0L,
        )

        assertEquals(1, events.size)
        val properties = events.single().second
        assertEquals("frozen_ms must count from the video stall start", 10_000L, properties["frozen_ms"])
        assertEquals(false, properties["recovered"])
    }

    @Test
    fun `a pipe stall keeps its detection-based frozen time`() {
        // Nothing moves from t=0: the playhead and the buffered edge both pin where they armed.
        reporter.onLivePlaybackStarted(profile = profile(), nowMs = 0L, positionMs = 1_000L)
        val start = reporter.onSample(
            nowMs = 7_000L,
            positionMs = 1_000L,
            bufferedPositionMs = 1_000L,
            state = LivePlaybackFreezePolicy.PlaybackState.BUFFERING,
            wantsToPlay = true,
            rebufferCount = 0,
            rebufferTotalMs = 0L,
        )
        assertEquals(
            LivePlaybackFreezePolicy.Decision.Start(LivePlaybackFreezePolicy.Kind.STALLED),
            start,
        )
        reporter.onLivePlaybackStopped(
            nowMs = 9_000L,
            positionMs = 1_000L,
            bufferedPositionMs = 1_000L,
            rebufferCount = 0,
            rebufferTotalMs = 0L,
        )

        assertEquals(1, events.size)
        // STALLED keeps the historical detection basis; only the video kind was rebased. The
        // fleet has been read with this meaning since the detector shipped.
        assertEquals(2_000L, events.single().second["frozen_ms"])
    }
}
