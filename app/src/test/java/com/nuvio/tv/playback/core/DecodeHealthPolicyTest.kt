package com.nuvio.tv.playback.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodeHealthPolicyTest {
    private val shape = DecodeStreamShape(videoFps = 50f)

    private fun playing(
        audible: Boolean = true,
        rendered: Long? = 250,
        lost: Long? = 0,
        audio: Long? = 150,
        windowMs: Long = 5_000,
        hasAudio: Boolean = true,
    ) = DecodeWindow(
        windowMs = windowMs,
        playing = true,
        hasAudioTrack = hasAudio,
        audible = audible,
        videoRenderedDelta = rendered,
        videoLostDelta = lost,
        audioProgressDelta = audio,
    )

    @Test
    fun `a sink discontinuity is an audio timestamp fault with its fault class`() {
        val policy = DecodeHealthPolicy()
        val report = policy.onSignal(DecodeSignal.AudioSinkDiscontinuity(), 0, shape)
        assertNotNull("the first discontinuity is reported", report)
        assertEquals("issue", DecodeIssue.AUDIO_TIMESTAMP_FAULT, report!!.issue)
        assertEquals("fault", DecodeFault.SINK_DISCONTINUITY, report.fault)
        assertEquals("sink errors counted", 1, report.sinkErrors)
    }

    @Test
    fun `an av offset past five seconds is an audio timestamp fault and below it is not`() {
        assertNull("4.9 s is drift, not a fault", DecodeHealthPolicy().onSignal(DecodeSignal.AvSyncOffset(4.9), 0))
        val report = DecodeHealthPolicy().onSignal(DecodeSignal.AvSyncOffset(-6.8), 0)
        assertEquals(DecodeIssue.AUDIO_TIMESTAMP_FAULT, report!!.issue)
        assertEquals(DecodeFault.AV_OFFSET, report.fault)
        assertEquals("1-10s", report.avOffsetBucket)
        assertEquals(">60s", DecodeHealthPolicy.avOffsetBucket(9_000.0))
        assertEquals("<1s", DecodeHealthPolicy.avOffsetBucket(0.2))
        assertEquals("10-60s", DecodeHealthPolicy.avOffsetBucket(30.0))
    }

    @Test
    fun `a video timestamp fault comes from the sanitizer signal only`() {
        val report = DecodeHealthPolicy().onSignal(DecodeSignal.TimestampFault(video = true, fault = DecodeFault.FOREIGN_BASE), 0)
        assertEquals(DecodeIssue.VIDEO_TIMESTAMP_FAULT, report!!.issue)
        assertEquals(DecodeFault.FOREIGN_BASE, report.fault)
    }

    @Test
    fun `audio and video decoder errors and sink errors map to their issues`() {
        assertEquals(
            DecodeIssue.AUDIO_DECODER_ERROR,
            DecodeHealthPolicy().onSignal(DecodeSignal.AudioDecoderError(), 0)!!.issue,
        )
        assertEquals(
            DecodeIssue.VIDEO_DECODER_ERROR,
            DecodeHealthPolicy().onSignal(DecodeSignal.VideoDecoderError(), 0)!!.issue,
        )
        val sink = DecodeHealthPolicy().onSignal(DecodeSignal.AudioSinkError(2), 0)!!
        assertEquals(DecodeIssue.AUDIO_DECODER_ERROR, sink.issue)
        assertEquals("a sink error is counted", 2, sink.sinkErrors)
    }

    @Test
    fun `decoder init over three seconds is slow and exactly three seconds is not`() {
        val policy = DecodeHealthPolicy()
        assertNull(policy.onSignal(DecodeSignal.DecoderInitialized(video = true, durationMs = 3_000), 0))
        val report = policy.onSignal(DecodeSignal.DecoderInitialized(video = false, durationMs = 4_200), 0)
        assertEquals(DecodeIssue.DECODER_INIT_SLOW, report!!.issue)
        assertEquals(4, report.secondsInBucket)
    }

    @Test
    fun `three underruns inside ten seconds are a burst and spread out ones are not`() {
        val spread = DecodeHealthPolicy()
        assertNull(spread.onSignal(DecodeSignal.AudioUnderrun(), 0))
        assertNull(spread.onSignal(DecodeSignal.AudioUnderrun(), 6_000))
        assertNull("the first one left the window", spread.onSignal(DecodeSignal.AudioUnderrun(), 12_000))

        val burst = DecodeHealthPolicy()
        assertNull(burst.onSignal(DecodeSignal.AudioUnderrun(), 0))
        assertNull(burst.onSignal(DecodeSignal.AudioUnderrun(), 2_000))
        val report = burst.onSignal(DecodeSignal.AudioUnderrun(), 4_000)
        assertEquals(DecodeIssue.AUDIO_UNDERRUN_BURST, report!!.issue)
        assertEquals(3, report.underruns)
    }

    @Test
    fun `a coalesced underrun count counts as that many underruns`() {
        val report = DecodeHealthPolicy().onSignal(DecodeSignal.AudioUnderrun(count = 4), 0)
        assertEquals(DecodeIssue.AUDIO_UNDERRUN_BURST, report!!.issue)
    }

    @Test
    fun `a drop burst of two seconds of frames is reported and a smaller one is not`() {
        val policy = DecodeHealthPolicy()
        // 50 fps: 2 s is 100 frames. Media3 batches its reports, so three close batches make the burst.
        assertNull(policy.onSignal(DecodeSignal.DroppedFrames(40), 0, shape))
        assertNull(policy.onSignal(DecodeSignal.DroppedFrames(40), 500, shape))
        val report = policy.onSignal(DecodeSignal.DroppedFrames(40), 1_000, shape)
        assertEquals(DecodeIssue.VIDEO_DROP_BURST, report!!.issue)
        assertEquals(120L, report.framesDropped)
    }

    @Test
    fun `drop batches far apart are separate bursts`() {
        val policy = DecodeHealthPolicy()
        repeat(6) { i -> assertNull("batch $i", policy.onSignal(DecodeSignal.DroppedFrames(40), i * 5_000L, shape)) }
    }

    @Test
    fun `a quarter of frames dropped over a five second window is a drop burst`() {
        val policy = DecodeHealthPolicy()
        assertTrue("24 percent is fine", policy.onWindow(playing(rendered = 190, lost = 60), shape).isEmpty())
        val reports = DecodeHealthPolicy().onWindow(playing(rendered = 150, lost = 50), shape)
        assertEquals(DecodeIssue.VIDEO_DROP_BURST, reports.single().issue)
        assertEquals(5, reports.single().secondsInBucket)
    }

    @Test
    fun `a window with too few frames never reports a drop ratio`() {
        assertTrue(DecodeHealthPolicy().onWindow(playing(rendered = 2, lost = 6), shape).isEmpty())
    }

    @Test
    fun `audio not playing needs eight seconds of flat audio while audible`() {
        val policy = DecodeHealthPolicy()
        assertTrue("5 s is not enough", policy.onWindow(playing(audio = 0), shape).isEmpty())
        val reports = policy.onWindow(playing(audio = 0), shape)
        assertEquals(DecodeIssue.AUDIO_NOT_PLAYING, reports.single().issue)
        assertEquals(10, reports.single().secondsInBucket)
    }

    @Test
    fun `audio that moves in between resets the silent time`() {
        val policy = DecodeHealthPolicy()
        assertTrue(policy.onWindow(playing(audio = 0), shape).isEmpty())
        assertTrue(policy.onWindow(playing(audio = 100), shape).isEmpty())
        assertTrue("the count started over", policy.onWindow(playing(audio = 0), shape).isEmpty())
    }

    @Test
    fun `a muted tile never reports audio not playing and does not bank silent time`() {
        val policy = DecodeHealthPolicy()
        repeat(4) { assertTrue("muted window $it", policy.onWindow(playing(audible = false, audio = 0), shape).isEmpty()) }
        assertTrue("turning audible starts from zero", policy.onWindow(playing(audible = true, audio = 0), shape).isEmpty())
    }

    @Test
    fun `a stream without an audio track is not silent audio`() {
        val policy = DecodeHealthPolicy()
        repeat(3) { assertTrue(policy.onWindow(playing(hasAudio = false, audio = 0), shape).isEmpty()) }
    }

    @Test
    fun `flat video while audio advances is reported after five seconds`() {
        val reports = DecodeHealthPolicy().onWindow(playing(rendered = 0, lost = 0, audio = 150), shape)
        assertEquals(DecodeIssue.VIDEO_FROZEN_AUDIO_PLAYING, reports.single().issue)
    }

    @Test
    fun `flat video with flat audio is a stall, not a frozen picture with sound`() {
        assertTrue(DecodeHealthPolicy().onWindow(playing(rendered = 0, audio = 0), shape).isEmpty())
    }

    @Test
    fun `a window while not playing resets everything and reports nothing`() {
        val policy = DecodeHealthPolicy()
        assertTrue(policy.onWindow(playing(audio = 0), shape).isEmpty())
        assertTrue(policy.onWindow(playing(audio = 0).copy(playing = false), shape).isEmpty())
        assertTrue("the paused window cleared the silent time", policy.onWindow(playing(audio = 0), shape).isEmpty())
    }

    @Test
    fun `timestamp repaired is never emitted by any input`() {
        val policy = DecodeHealthPolicy(DecodeHealthBudget(max = 100))
        val signals = listOf(
            DecodeSignal.AudioSinkDiscontinuity(), DecodeSignal.AudioSinkError(), DecodeSignal.AudioDecoderError(),
            DecodeSignal.VideoDecoderError(), DecodeSignal.AudioUnderrun(5), DecodeSignal.DroppedFrames(500),
            DecodeSignal.DecoderInitialized(true, 9_000), DecodeSignal.AvSyncOffset(99.0),
            DecodeSignal.TimestampFault(true, DecodeFault.PTS_WRAP),
        )
        val issues = signals.mapNotNull { policy.onSignal(it, 0, shape)?.issue }.toSet() +
            policy.onWindow(playing(rendered = 0, lost = 100, audio = 0), shape).map { it.issue }
        assertTrue(DecodeIssue.TIMESTAMP_REPAIRED !in issues)
    }

    @Test
    fun `an issue is reported once per tune and again after a new tune`() {
        val policy = DecodeHealthPolicy(DecodeHealthBudget(max = 100))
        assertNotNull(policy.onSignal(DecodeSignal.VideoDecoderError(), 0))
        assertNull("same tune", policy.onSignal(DecodeSignal.VideoDecoderError(), 10))
        policy.startAttempt()
        assertNull("a reconnect inside the tune keeps the dedupe", policy.onSignal(DecodeSignal.VideoDecoderError(), 20))
        policy.startTune()
        assertNotNull("next channel", policy.onSignal(DecodeSignal.VideoDecoderError(), 30))
    }

    @Test
    fun `a session reports at most five issues however many tunes`() {
        val policy = DecodeHealthPolicy()
        var emitted = 0
        repeat(10) {
            policy.startTune()
            if (policy.onSignal(DecodeSignal.VideoDecoderError(), 0) != null) emitted++
        }
        assertEquals("cap per session", 5, emitted)
        assertEquals("every detected issue is still counted for the summary", 10, policy.totals().issues[DecodeIssue.VIDEO_DECODER_ERROR])
    }

    @Test
    fun `a capped issue is not retried once budget would free up`() {
        val budget = DecodeHealthBudget(max = 0)
        val policy = DecodeHealthPolicy(budget)
        assertNull(policy.onSignal(DecodeSignal.AudioDecoderError(), 0))
        assertEquals(1, policy.totals().issueCount)
    }

    @Test
    fun `multiview tiles dedupe per tile and share one cap of five`() {
        val budget = DecodeHealthBudget()
        val tiles = List(4) { DecodeHealthPolicy(budget, dedupePerSession = true) }
        val all = mutableListOf<DecodeHealthReport>()
        // Four tiles on a bad provider, each seeing every issue, across several channel changes.
        repeat(3) {
            tiles.forEach { tile ->
                tile.startTune()
                listOf(
                    DecodeSignal.AudioSinkDiscontinuity(), DecodeSignal.VideoDecoderError(),
                    DecodeSignal.AudioDecoderError(), DecodeSignal.DecoderInitialized(true, 5_000),
                ).forEach { s -> tile.onSignal(s, 0)?.let(all::add) }
            }
        }
        assertEquals("one cap for the whole screen", 5, all.size)
        assertEquals("the first tile used it up with distinct issues", 4, all.map { it.issue }.toSet().size)
    }

    @Test
    fun `a tile keeps its dedupe across tunes in multiview`() {
        val tile = DecodeHealthPolicy(DecodeHealthBudget(), dedupePerSession = true)
        assertNotNull(tile.onSignal(DecodeSignal.VideoDecoderError(), 0))
        tile.startTune()
        assertNull(tile.onSignal(DecodeSignal.VideoDecoderError(), 0))
    }

    @Test
    fun `totals count frames underruns and sink errors across tunes`() {
        val policy = DecodeHealthPolicy(DecodeHealthBudget(max = 100))
        policy.onSignal(DecodeSignal.DroppedFrames(30), 0, shape)
        policy.onSignal(DecodeSignal.AudioUnderrun(2), 0)
        policy.onSignal(DecodeSignal.AudioSinkDiscontinuity(3), 0)
        policy.startTune()
        policy.addDroppedFrames(12)
        val totals = policy.totals()
        assertEquals(42L, totals.framesDropped)
        assertEquals(2, totals.underruns)
        assertEquals(3, totals.sinkErrors)
        assertEquals("AUDIO_TIMESTAMP_FAULT:1", totals.issueCodes())
    }

    @Test
    fun `the report carries per tune counts that reset on a new tune`() {
        val policy = DecodeHealthPolicy(DecodeHealthBudget(max = 100))
        policy.onSignal(DecodeSignal.AudioSinkDiscontinuity(2), 0)
        policy.startTune()
        val report = policy.onSignal(DecodeSignal.AudioDecoderError(), 0)!!
        assertEquals(0, report.sinkErrors)
    }

    @Test
    fun `format facts never produce an issue`() {
        assertNull(DecodeHealthPolicy().onSignal(DecodeSignal.FormatFacts(audioCodec = "eac3"), 0))
    }

    @Test
    fun `b frames map from reorder depth to yes no or unknown`() {
        assertEquals("yes", DecodeStreamShape.bFramesOf(2))
        assertEquals("no", DecodeStreamShape.bFramesOf(0))
        assertEquals("unknown", DecodeStreamShape.bFramesOf(null))
        assertEquals("unknown", DecodeStreamShape.bFramesOf(-1))
        assertEquals("unknown", DecodeStreamShape.bFramesOf(17))
        assertEquals("a stream without video reports no frame type", null, DecodeStreamShape(reorderDepth = 2).bFrames())
        assertEquals("yes", DecodeStreamShape(hasVideo = true, reorderDepth = 2).bFrames())
        assertEquals("unknown", DecodeStreamShape(hasVideo = true).bFrames())
    }

    @Test
    fun `each video tune counts once by frame type and audio only tunes count none`() {
        val policy = DecodeHealthPolicy()
        policy.observeFrameType(true, 2)
        policy.observeFrameType(true, 0) // latest wins within a tune
        policy.startTune()
        policy.observeFrameType(true, null)
        policy.startTune()
        policy.observeFrameType(false, null)
        policy.startTune()
        policy.observeFrameType(true, 4)
        val totals = policy.totals()
        assertEquals(1, totals.bFramesYes)
        assertEquals(1, totals.bFramesNo)
        assertEquals(1, totals.bFramesUnknown)
    }

    @Test
    fun `the coalescer lets the first through and folds a flood into counts`() {
        val c = DecodeSignalCoalescer(intervalMs = 1_000)
        assertEquals(1, c.offer(0))
        assertNull(c.offer(100))
        assertNull(c.offer(200))
        assertEquals("the held ones are carried", 3, c.offer(1_100))
    }

    @Test
    fun `a tile context starts muted, shares its screen budget and reports its tile count`() {
        val link = DecodeHealthTileLink(DecodeHealthBudget(), tileCount = { 4 })
        val ctx = DecodeHealthContext.forTile(TileHostConfig(bufferBytes = 1, decodeHealth = link))
        assertTrue(ctx.tile)
        assertEquals(false, ctx.startsAudible)
        assertEquals(4, ctx.tileCount())
        assertTrue("same budget object", ctx.budget === link.budget)
        val single = DecodeHealthContext.forTile(null)
        assertEquals(false, single.tile)
        assertEquals(true, single.startsAudible)
    }

    @Test
    fun `stream shape helpers produce short identifiers`() {
        assertEquals("eac3", DecodeStreamShape.audioCodecWire("audio/eac3"))
        assertEquals("ac-4", DecodeStreamShape.audioCodecWire("audio/ac-4"))
        assertNull(DecodeStreamShape.audioCodecWire("  "))
        assertEquals(StreamCodecCode.HEVC, DecodeStreamShape.videoCodecCode("video/hevc"))
        assertEquals(StreamCodecCode.OTHER, DecodeStreamShape.videoCodecCode("video/dolby-vision"))
        assertEquals("50", DecodeStreamShape(videoFps = 50f).fpsBucket())
        assertEquals("le25", DecodeStreamShape(videoFps = 24f).fpsBucket())
        assertEquals("gt50", DecodeStreamShape(videoFps = 60f).fpsBucket())
    }
}
