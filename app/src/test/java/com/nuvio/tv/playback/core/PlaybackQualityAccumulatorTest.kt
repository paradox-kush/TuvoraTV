package com.nuvio.tv.playback.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The clean live stack reported no playback quality at all (no startup time, stalls, freezes or
 * engine time), so "choppy"/"slow" reports were invisible in telemetry. One summary per session,
 * sent once at the end, numbers and enums only.
 */
class PlaybackQualityAccumulatorTest {
    private var clock = 0L
    private val accumulator = PlaybackQualityAccumulator(now = { clock })

    @Test
    fun `a watched channel reports startup bucket watch time and engine time`() {
        observe(generation = 1, state = PlaybackState.STARTING_PRIMARY)
        advance(1_500)
        observe(generation = 1, state = PlaybackState.PLAYING, playing = true, firstFrame = true, engine = EngineType.LIBMPV)
        advance(60_000)
        val summary = accumulator.finish()

        assertEquals(1, summary.views)
        assertEquals(1, summary.viewsStarted)
        assertEquals("1.5 s startup lands in the <2 s bucket", 1, summary.startupBuckets[2])
        assertEquals(60_000L, summary.watchMs)
        assertEquals(60_000L, summary.engineMsLibmpv)
        assertEquals(0L, summary.engineMsMedia3)
    }

    @Test
    fun `channel surfing separates quick zaps from waiting exits`() {
        observe(generation = 1, state = PlaybackState.STARTING_PRIMARY)
        advance(400)
        observe(generation = 2, state = PlaybackState.STARTING_PRIMARY)
        advance(2_500)
        observe(generation = 3, state = PlaybackState.STARTING_PRIMARY)
        advance(800)
        observe(generation = 3, state = PlaybackState.PLAYING, playing = true, firstFrame = true)
        val summary = accumulator.finish()

        assertEquals(3, summary.views)
        assertEquals(1, summary.viewsStarted)
        assertEquals("a sub-second zap is browsing, not a failure", 1, summary.zapsUnder1s)
        assertEquals("waited 2.5 s and left before a picture", 1, summary.exitsBeforeStart)
    }

    @Test
    fun `stalls after the picture starts are rebuffers with duration`() {
        observe(generation = 1, state = PlaybackState.PLAYING, playing = true, firstFrame = true)
        advance(10_000)
        observe(generation = 1, state = PlaybackState.PLAYING, buffering = true, firstFrame = true)
        advance(3_000)
        observe(generation = 1, state = PlaybackState.PLAYING, playing = true, firstFrame = true)
        advance(5_000)
        val summary = accumulator.finish()

        assertEquals(1, summary.rebufferCount)
        assertEquals(3_000L, summary.rebufferMs)
        assertEquals(15_000L, summary.watchMs)
    }

    @Test
    fun `recovery activity and failures are counted by code without values`() {
        observe(generation = 1, state = PlaybackState.PLAYING, playing = true, firstFrame = true)
        advance(1_000)
        observe(
            generation = 1,
            state = PlaybackState.LIVE_RECONNECTING,
            reconnecting = true,
            firstFrame = true,
            failure = FailureCode.NO_PROGRESS,
        )
        advance(1_000)
        observe(generation = 1, state = PlaybackState.HANDING_OFF_ONCE, firstFrame = true, failure = FailureCode.NO_PROGRESS)
        advance(1_000)
        observe(generation = 1, state = PlaybackState.FAILED, firstFrame = true, failure = FailureCode.AUDIO_OUTPUT_FAILED)
        val summary = accumulator.finish()

        assertEquals(1, summary.reconnects)
        assertEquals(1, summary.handoffs)
        assertEquals(1, summary.freezes)
        assertEquals(1, summary.playbackFailures)
        assertEquals(0, summary.startupFailures)
        assertEquals("NO_PROGRESS:1,AUDIO_OUTPUT_FAILED:1", summary.failureCodes)
    }

    @Test
    fun `display rate switches and resolution time are reported`() {
        observe(
            generation = 1,
            state = PlaybackState.PLAYING,
            playing = true,
            firstFrame = true,
            dimensions = VideoDimensions(720, 576),
            output = PlaybackOutputStatus.APPLIED,
        )
        advance(4_000)
        observe(
            generation = 1,
            state = PlaybackState.PLAYING,
            playing = true,
            firstFrame = true,
            dimensions = VideoDimensions(1920, 1080),
            output = PlaybackOutputStatus.APPLIED,
        )
        advance(6_000)
        val summary = accumulator.finish()

        assertEquals(1, summary.displayRateSwitches)
        assertEquals(4_000L, summary.resolutionMs[0])
        assertEquals(6_000L, summary.resolutionMs[2])
    }

    @Test
    fun `properties are numbers and enums only and avoid the privacy filter key traps`() {
        observe(generation = 1, state = PlaybackState.PLAYING, playing = true, firstFrame = true)
        advance(1_000)
        val properties = accumulator.finish().toProperties()

        assertFalse(properties.keys.any { it == "code" || it.endsWith("_url") || it.endsWith("_uri") })
        assertFalse(properties.keys.any { key -> listOf("token", "secret", "password", "cookie").any { key.contains(it) } })
        assertFalse(properties.values.any { it is String && it.contains("://") })
        assertEquals(1, properties["views"])
    }

    @Test
    fun `an empty session reports nothing`() {
        assertNull(PlaybackQualityAccumulator(now = { 0L }).finish().takeIf { it.views > 0 })
    }

    private fun advance(ms: Long) {
        clock += ms
    }

    private fun observe(
        generation: Long,
        state: PlaybackState,
        playing: Boolean = false,
        buffering: Boolean = false,
        reconnecting: Boolean = false,
        firstFrame: Boolean = false,
        engine: EngineType = EngineType.LIBMPV,
        dimensions: VideoDimensions? = null,
        output: PlaybackOutputStatus = PlaybackOutputStatus.NOT_REQUESTED,
        failure: FailureCode? = null,
    ) {
        accumulator.observe(
            PlaybackSnapshot(
                generation = generation,
                state = state,
                graph = PlaybackGraph(
                    id = "g",
                    engine = engine,
                    outputProfile = if (engine == EngineType.LIBMPV) {
                        GraphOutputProfile.MPV_DIRECT
                    } else {
                        GraphOutputProfile.MEDIA3_STANDARD
                    },
                    decoderMode = DecoderMode.HARDWARE,
                    audioMode = AudioMode.DECODE,
                    surfaceMode = SurfaceMode.SURFACE_VIEW,
                ),
                isPlaying = playing,
                isBuffering = buffering,
                isReconnecting = reconnecting,
                progress = PlaybackProgressEvidence(renderedVideoFrame = firstFrame),
                tracks = TrackSummary(videoDimensions = dimensions),
                playbackOutputStatus = output,
                failure = failure?.let {
                    PlaybackFailure(it, FailureDomain.UNKNOWN, FailurePhase.PLAYBACK, Retryability.FATAL)
                },
            ),
        )
    }
}
