package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the Onn 4K channel-switch stutter/black (root-caused on-device 2026-08-21):
 * ExoPlayer reused a 4K-sized video codec for smaller channels, and the Amlogic decoder threw
 * CodecException 0x80000000 on the in-place downsize. The renderer discards the codec on a
 * resolution change; this pins that decision.
 *
 * NOTE: JUnit argument order is assertEquals(message, expected, actual) — opposite of kotlin.test.
 */
class LiveCodecReusePolicyTest {

    @Test
    fun `same resolution reuses the codec`() {
        assertFalse(LiveCodecReusePolicy.resolutionChanged(1920, 1080, 1920, 1080))
    }

    @Test
    fun `a 4K to SD change forces a fresh codec`() {
        // The exact transition that threw 0x80000000 on the Onn: 4096x2304 -> 1024x576.
        assertTrue(LiveCodecReusePolicy.resolutionChanged(4096, 2304, 1024, 576))
    }

    @Test
    fun `HD to SD (and back) forces a fresh codec`() {
        assertTrue(LiveCodecReusePolicy.resolutionChanged(1920, 1080, 720, 576))
        assertTrue(LiveCodecReusePolicy.resolutionChanged(720, 576, 1920, 1080))
    }

    @Test
    fun `a change in only one dimension still counts`() {
        assertTrue(LiveCodecReusePolicy.resolutionChanged(1920, 1080, 1440, 1080))
        assertTrue(LiveCodecReusePolicy.resolutionChanged(1920, 1080, 1920, 800))
    }

    @Test
    fun `unknown dimensions defer to normal reuse (no spurious re-init)`() {
        // Format.NO_VALUE is -1; a missing size on either side must not force a discard.
        assertFalse(LiveCodecReusePolicy.resolutionChanged(-1, -1, 1920, 1080))
        assertFalse(LiveCodecReusePolicy.resolutionChanged(1920, 1080, -1, -1))
        assertFalse(LiveCodecReusePolicy.resolutionChanged(0, 0, 0, 0))
    }
}
