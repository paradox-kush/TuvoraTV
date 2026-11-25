package com.nuvio.tv.playback.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accumulator is the one place that sees every clean-pipeline snapshot, so it is where playback
 * telemetry (first frame, hand-off, stall) is derived. These tests use a recording sink.
 */
class PlaybackSignalsTest {
    private var clock = 0L
    private val log = ArrayList<String>()
    private val sink = object : PlaybackSignalSink {
        override fun firstFrame(kind: PlaybackSignalKind, engine: EngineType?, ms: Long) { log += "ff:$kind:$engine:$ms" }
        override fun handoff(from: EngineType, to: EngineType, cause: FailureCode?) { log += "handoff:$from:$to:$cause" }
        override fun stall(kind: PlaybackSignalKind, outcome: PlaybackStallOutcome, ms: Long) { log += "stall:$kind:$outcome:$ms" }
    }

    private fun accumulator(tile: Boolean = false) = PlaybackQualityAccumulator({ clock }, sink, tile)

    @Test
    fun `first frame reports the time from the request with kind and engine`() {
        val a = accumulator()
        a.observe(snap(1, PlaybackState.STARTING_PRIMARY))
        clock += 1_840
        a.observe(snap(1, PlaybackState.PLAYING, frame = true))
        clock += 5_000
        a.observe(snap(1, PlaybackState.PLAYING, frame = true)) // a later snapshot is not a second first frame
        assertEquals(listOf("ff:LIVE:MEDIA3:1840"), log)
    }

    @Test
    fun `the surface decides the kind`() {
        accumulator(tile = true).also { it.observe(snap(1, PlaybackState.PLAYING, frame = true)) }
        accumulator().also { it.observe(snap(1, PlaybackState.PLAYING, frame = true, profile = SessionProfile.GUIDE)) }
        accumulator().also { it.observe(snap(1, PlaybackState.PLAYING, frame = true, content = ContentType.VOD)) }
        accumulator().also { it.observe(snap(1, PlaybackState.PLAYING, frame = true, content = ContentType.CATCH_UP)) }
        assertEquals(
            listOf("ff:MULTIVIEW_TILE:MEDIA3:0", "ff:PREVIEW:MEDIA3:0", "ff:VOD:MEDIA3:0", "ff:VOD:MEDIA3:0"),
            log,
        )
    }

    @Test
    fun `the app's own live buffer playlist is not a source start`() {
        accumulator().observe(snap(1, PlaybackState.PLAYING, frame = true, scheme = "file", content = ContentType.VOD))
        assertTrue("nothing reported for a file source", log.isEmpty())
    }

    @Test
    fun `a hand-off is reported once the new graph is chosen with its cause and both engines`() {
        val a = accumulator()
        a.observe(snap(1, PlaybackState.PLAYING, frame = true, engine = EngineType.MEDIA3))
        log.clear()
        a.observe(snap(1, PlaybackState.HANDING_OFF_ONCE, engine = EngineType.MEDIA3, failure = FailureCode.VIDEO_DECODER_FAILED))
        assertTrue("not before the new graph exists", log.isEmpty())
        a.observe(snap(1, PlaybackState.SELECTING_GRAPH, engine = EngineType.MEDIA3, failure = FailureCode.VIDEO_DECODER_FAILED))
        assertTrue("still the old graph", log.isEmpty())
        a.observe(snap(1, PlaybackState.ATTACHING_SURFACE, engine = EngineType.LIBMPV))
        a.observe(snap(1, PlaybackState.PLAYING, frame = true, engine = EngineType.LIBMPV))
        assertEquals(listOf("handoff:MEDIA3:LIBMPV:VIDEO_DECODER_FAILED"), log.filter { it.startsWith("handoff") })
    }

    @Test
    fun `a hand-off that ends in failure reports no fallback`() {
        val a = accumulator()
        a.observe(snap(1, PlaybackState.PLAYING, frame = true, engine = EngineType.MEDIA3))
        a.observe(snap(1, PlaybackState.HANDING_OFF_ONCE, engine = EngineType.MEDIA3, failure = FailureCode.NETWORK_TIMEOUT))
        a.observe(snap(1, PlaybackState.FAILED, engine = EngineType.MEDIA3, failure = FailureCode.NETWORK_TIMEOUT))
        assertTrue(log.none { it.startsWith("handoff") })
    }

    @Test
    fun `a buffering stall that resumes is recovered with its duration`() {
        val a = accumulator()
        a.observe(snap(1, PlaybackState.PLAYING, frame = true))
        clock += 10_000
        a.observe(snap(1, PlaybackState.PLAYING, frame = true, buffering = true))
        clock += 3_200
        a.observe(snap(1, PlaybackState.PLAYING, frame = true))
        assertEquals(listOf("ff:LIVE:MEDIA3:0", "stall:LIVE:RECOVERED:3200"), log)
    }

    @Test
    fun `a stall that ends in failure is gave up and a blip under a second is nothing`() {
        val a = accumulator()
        a.observe(snap(1, PlaybackState.PLAYING, frame = true))
        clock += 100
        a.observe(snap(1, PlaybackState.PLAYING, frame = true, buffering = true))
        clock += 400
        a.observe(snap(1, PlaybackState.PLAYING, frame = true))
        assertTrue("0.4 s blip is routine", log.none { it.startsWith("stall") })
        a.observe(snap(1, PlaybackState.LIVE_RECONNECTING, frame = true, reconnecting = true))
        clock += 30_000
        a.observe(snap(1, PlaybackState.FAILED, frame = true, failure = FailureCode.LIVE_RECONNECT_EXHAUSTED))
        assertEquals("stall:LIVE:GAVE_UP:30000", log.last())
    }

    @Test
    fun `zapping away or leaving during a stall is abandoned`() {
        val a = accumulator()
        a.observe(snap(1, PlaybackState.PLAYING, frame = true))
        clock += 100
        a.observe(snap(1, PlaybackState.PLAYING, frame = true, buffering = true))
        clock += 8_000
        a.observe(snap(2, PlaybackState.RESOLVING))
        a.observe(snap(2, PlaybackState.PLAYING, frame = true))
        assertEquals("stall:LIVE:ABANDONED:8000", log.last { it.startsWith("stall") })
    }

    @Test
    fun `releasing the host while the picture is stalled is abandoned`() {
        val a = accumulator()
        a.observe(snap(1, PlaybackState.PLAYING, frame = true))
        clock += 100
        a.observe(snap(1, PlaybackState.PLAYING, frame = true, buffering = true))
        clock += 12_000
        a.finish()
        assertEquals("stall:LIVE:ABANDONED:12000", log.last())
    }

    @Test
    fun `stopping during a stall is abandoned`() {
        val a = accumulator()
        a.observe(snap(1, PlaybackState.PLAYING, frame = true))
        a.observe(snap(1, PlaybackState.PLAYING, frame = true, buffering = true))
        clock += 5_000
        a.observe(snap(1, PlaybackState.STOPPED))
        assertEquals("stall:LIVE:ABANDONED:5000", log.last())
    }

    @Test
    fun `a sink that throws never breaks the pipeline`() {
        val a = PlaybackQualityAccumulator(
            { clock },
            object : PlaybackSignalSink {
                override fun firstFrame(kind: PlaybackSignalKind, engine: EngineType?, ms: Long) = error("telemetry down")
            },
        )
        a.observe(snap(1, PlaybackState.PLAYING, frame = true))
        assertEquals(1, a.finish().viewsStarted)
    }

    private fun snap(
        generation: Long,
        state: PlaybackState,
        frame: Boolean = false,
        buffering: Boolean = false,
        reconnecting: Boolean = false,
        engine: EngineType = EngineType.MEDIA3,
        failure: FailureCode? = null,
        profile: SessionProfile = SessionProfile.FULLSCREEN,
        content: ContentType = ContentType.LIVE,
        scheme: String = "https",
    ) = PlaybackSnapshot(
        generation = generation,
        state = state,
        profile = profile,
        requestSummary = RequestSummary(
            scheme = scheme,
            contentType = content,
            hasAuthorization = false,
            hasCustomHeaders = false,
            hasCookies = false,
            hasUserAgent = false,
            hasReferer = false,
            hasOrigin = false,
            hasDrm = false,
            redirectPolicy = RedirectPolicy.FOLLOW,
            crossHostAuthorization = CrossHostAuthorization.STRIP,
            tlsPolicy = TlsPolicy.STRICT,
            dnsPolicy = DnsPolicy.SYSTEM,
            providerConnectionConstrained = false,
        ),
        graph = PlaybackGraph(
            id = "g-$engine",
            engine = engine,
            outputProfile = if (engine == EngineType.LIBMPV) GraphOutputProfile.MPV_DIRECT else GraphOutputProfile.MEDIA3_STANDARD,
            decoderMode = DecoderMode.HARDWARE,
            audioMode = AudioMode.DECODE,
            surfaceMode = SurfaceMode.SURFACE_VIEW,
        ),
        isBuffering = buffering,
        isReconnecting = reconnecting,
        isPlaying = state == PlaybackState.PLAYING && !buffering,
        progress = PlaybackProgressEvidence(renderedVideoFrame = frame),
        failure = failure?.let { PlaybackFailure(it, FailureDomain.UNKNOWN, FailurePhase.PLAYBACK, Retryability.FATAL) },
    )
}
