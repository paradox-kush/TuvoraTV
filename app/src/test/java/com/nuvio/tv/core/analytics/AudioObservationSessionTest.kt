package com.nuvio.tv.core.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure, generation-guarded observation state machine — evidence in, status out. */
class AudioObservationSessionTest {

    private fun session(mpv: Boolean = false) =
        AudioObservationSession(generation = 1L, minEligibleMs = 3_000L, maxWindowMs = 15_000L, progressUnobservable = mpv)

    /** Decoding (E-AC-3 -> PCM on a PCM-only sink) that renders buffers is PROGRESS, not a failure. */
    @Test
    fun `rendered buffers advancing over eligible time is progress observed`() {
        val s = session()
        s.onPlaybackState(1L, atMs = 0, playing = true, buffering = false, suppressed = false)
        s.onRenderedBuffers(1L, atMs = 0, count = 100)
        s.onRenderedBuffers(1L, atMs = 3_500, count = 900) // decoder is emitting PCM
        assertTrue(s.isDecidable(3_500))
        val r = s.evaluate(3_500)
        assertEquals(AudioStatus.PROGRESS_OBSERVED, r.status)
        assertEquals(AudioPipelineProgress.OBSERVED, r.progress)
        assertEquals(800L, r.renderedBufferDelta)
    }

    @Test
    fun `eligible time with no rendered progress is progress not observed`() {
        val s = session()
        s.onPlaybackState(1L, 0, playing = true, buffering = false, suppressed = false)
        s.onRenderedBuffers(1L, 0, count = 100)
        s.onRenderedBuffers(1L, 4_000, count = 100) // stuck
        val r = s.evaluate(4_000)
        assertEquals(AudioStatus.PROGRESS_NOT_OBSERVED, r.status)
        assertEquals(0L, r.renderedBufferDelta)
        assertTrue(r.eligibleObservationMs >= 3_000)
    }

    /** Buffering past the nominal window must be INSUFFICIENT_OBSERVATION, never a false NOT_OBSERVED. */
    @Test
    fun `buffering beyond four seconds yields insufficient observation`() {
        val s = session()
        s.onPlaybackState(1L, 0, playing = false, buffering = true, suppressed = false)
        s.onRenderedBuffers(1L, 5_000, count = 100) // still buffering at 5s
        val r = s.evaluate(5_000)
        assertEquals(AudioStatus.INSUFFICIENT_OBSERVATION, r.status)
        assertEquals(0L, r.eligibleObservationMs)
        assertTrue("buffering time is accounted", r.bufferingMs >= 4_000)
        // ...and the adapter should keep waiting rather than emit early:
        assertEquals(false, s.isDecidable(5_000))
    }

    @Test
    fun `a track or route change re-arms and a successful recovery is reported as progress`() {
        val s = session()
        s.onPlaybackState(1L, 0, playing = true, buffering = false, suppressed = false)
        s.onRenderedBuffers(1L, 0, count = 100)
        s.onRenderedBuffers(1L, 2_000, count = 100) // 2s of silence pre-change
        s.onDisruptiveChange(1L, 2_000)              // user switches audio track / route flips
        s.onRenderedBuffers(1L, 2_000, count = 100)  // re-baseline here
        s.onRenderedBuffers(1L, 5_600, count = 700)  // audio now flowing after recovery
        val r = s.evaluate(5_600)
        assertEquals(AudioStatus.PROGRESS_OBSERVED, r.status)
        assertEquals(1, r.rearmCount)
        assertEquals(600L, r.renderedBufferDelta) // measured from the re-baseline, not the old silence
    }

    @Test
    fun `a late callback from a previous generation is rejected`() {
        val s = AudioObservationSession(generation = 5L, minEligibleMs = 3_000L)
        s.onPlaybackState(5L, 0, playing = true, buffering = false, suppressed = false)
        s.onRenderedBuffers(5L, 0, count = 100)
        s.onRenderedBuffers(4L, 1_000, count = 100_000) // STALE generation -> must be ignored
        s.onRenderedBuffers(5L, 3_500, count = 100)     // real: no progress
        val r = s.evaluate(3_500)
        // If the stale huge count had leaked in, this would be PROGRESS_OBSERVED. It must not.
        assertEquals(AudioStatus.PROGRESS_NOT_OBSERVED, r.status)
        assertEquals(0L, r.renderedBufferDelta)
    }

    @Test
    fun `mpv keeps progress unknown even with eligible time and never claims silence`() {
        val s = session(mpv = true)
        s.onPlaybackState(1L, 0, playing = true, buffering = false, suppressed = false)
        s.onRenderedBuffers(1L, 0, count = 100) // even if a count leaks in, mpv stays unknown
        s.onRenderedBuffers(1L, 5_000, count = 100)
        val r = s.evaluate(5_000)
        assertEquals(AudioStatus.UNKNOWN, r.status)
        assertEquals(AudioPipelineProgress.UNKNOWN, r.progress)
    }

    @Test
    fun `an audio pipeline error is output_error regardless of progress`() {
        val s = session()
        s.onPlaybackState(1L, 0, playing = true, buffering = false, suppressed = false)
        s.onRenderedBuffers(1L, 0, count = 100)
        s.onAudioError(1L, 500, code = "audio_track_init")
        s.onRenderedBuffers(1L, 3_500, count = 900) // even with progress, an error dominates
        assertTrue(s.isDecidable(3_500))
        val r = s.evaluate(3_500)
        assertEquals(AudioStatus.OUTPUT_ERROR, r.status)
        assertEquals("audio_track_init", r.errorCode)
    }

    @Test
    fun `re-arms are bounded so a flapping stream cannot observe forever`() {
        val s = AudioObservationSession(generation = 1L, minEligibleMs = 3_000L, maxRearms = 2)
        s.onPlaybackState(1L, 0, playing = true, buffering = false, suppressed = false)
        s.onDisruptiveChange(1L, 100)
        s.onDisruptiveChange(1L, 200)
        s.onDisruptiveChange(1L, 300) // beyond maxRearms -> ignored
        assertEquals(2, s.evaluate(300).rearmCount)
    }
}
