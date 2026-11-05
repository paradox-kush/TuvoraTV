package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TV twin of the KMP ResumeLoadPolicyTest. */
class ResumeLoadPolicyTest {

    @Test
    fun `a vod load with a saved position is a resume`() {
        assertTrue("saved position, not live", ResumeLoadPolicy.isResumeLoad(initialPositionMs = 822_000L, isLive = false))
    }

    @Test
    fun `a load from zero or a live channel is not a resume`() {
        assertFalse("from zero", ResumeLoadPolicy.isResumeLoad(initialPositionMs = 0L, isLive = false))
        assertFalse("live", ResumeLoadPolicy.isResumeLoad(initialPositionMs = 822_000L, isLive = true))
    }

    @Test
    fun `start over is offered once a resume has waited long enough without a frame`() {
        assertFalse(
            "not before the threshold",
            ResumeLoadPolicy.offerStartOver(isResumeLoad = true, firstFrameShown = false, loadingForMs = 14_999L),
        )
        assertTrue(
            "at the threshold",
            ResumeLoadPolicy.offerStartOver(isResumeLoad = true, firstFrameShown = false, loadingForMs = 15_000L),
        )
    }

    @Test
    fun `start over is never offered once playback started or for a fresh start`() {
        assertFalse(
            "already playing",
            ResumeLoadPolicy.offerStartOver(isResumeLoad = true, firstFrameShown = true, loadingForMs = 60_000L),
        )
        assertFalse(
            "a fresh start has nothing to fall back to",
            ResumeLoadPolicy.offerStartOver(isResumeLoad = false, firstFrameShown = false, loadingForMs = 60_000L),
        )
    }

    @Test
    fun `the threshold is fifteen seconds`() {
        assertEquals("threshold", 15_000L, ResumeLoadPolicy.START_OVER_OFFER_AFTER_MS)
    }
}
