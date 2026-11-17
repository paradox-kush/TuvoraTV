package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.R
import com.nuvio.tv.playback.ui.LivePlaybackUiStatusCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The docked preview told the truth badly: its retry line was `status.name.lowercase()` ("reconnecting",
 * "handing off", developer wording, unlocalised), nothing said "Tuning" before the first frame, and nothing said
 * which channel was playing once focus moved elsewhere (UX333, UX356, UX357, UX327).
 */
class GuidePreviewStatusPolicyTest {

    @Test
    fun `every status code maps to a sentence or to nothing, never to the enum name`() {
        for (code in LivePlaybackUiStatusCode.entries) {
            val res = GuidePreviewStatusPolicy.statusRes(code)
            if (res != null) assertNotNull("$code has a string resource", res)
        }
        assertEquals(R.string.iptv_guide_tuning, GuidePreviewStatusPolicy.statusRes(LivePlaybackUiStatusCode.RESOLVING))
        assertEquals(R.string.iptv_guide_tuning, GuidePreviewStatusPolicy.statusRes(LivePlaybackUiStatusCode.STARTING))
        assertEquals(R.string.iptv_guide_preview_reconnecting, GuidePreviewStatusPolicy.statusRes(LivePlaybackUiStatusCode.RECONNECTING))
        assertEquals(R.string.iptv_guide_preview_reconnecting, GuidePreviewStatusPolicy.statusRes(LivePlaybackUiStatusCode.RECOVERING))
        assertNull("paused is the player's own business", GuidePreviewStatusPolicy.statusRes(LivePlaybackUiStatusCode.PAUSED))
    }

    @Test
    fun `tuning shows until the first frame`() {
        assertEquals(
            GuidePreviewStatusPolicy.Label.Tuning,
            GuidePreviewStatusPolicy.label(tuning = true, playingName = "BBC One", aimedIsPlaying = true, failed = false),
        )
    }

    @Test
    fun `focus on another channel names the one that is playing`() {
        assertEquals(
            GuidePreviewStatusPolicy.Label.Playing("BBC One"),
            GuidePreviewStatusPolicy.label(tuning = false, playingName = "BBC One", aimedIsPlaying = false, failed = false),
        )
    }

    @Test
    fun `focus on the playing channel needs no label`() {
        assertEquals(
            GuidePreviewStatusPolicy.Label.None,
            GuidePreviewStatusPolicy.label(tuning = false, playingName = "BBC One", aimedIsPlaying = true, failed = false),
        )
    }

    @Test
    fun `a failed channel is named by its failure copy not by Playing`() {
        assertEquals(
            GuidePreviewStatusPolicy.Label.None,
            GuidePreviewStatusPolicy.label(tuning = false, playingName = "BBC One", aimedIsPlaying = false, failed = true),
        )
    }

    @Test
    fun `nothing playing and nothing tuning shows nothing`() {
        assertEquals(
            GuidePreviewStatusPolicy.Label.None,
            GuidePreviewStatusPolicy.label(tuning = false, playingName = null, aimedIsPlaying = false, failed = false),
        )
    }

    @Test
    fun `a failure on the focused channel offers OK as Retry`() {
        assertEquals(true, GuidePreviewStatusPolicy.offersRetryHint(failureShown = true))
        assertEquals(false, GuidePreviewStatusPolicy.offersRetryHint(failureShown = false))
    }

    /** Device pass: during a reconnect the docked pill read "Tuning…" (the spinner won), so the attempt line never showed. */
    @Test
    fun `the docked pill says reconnecting while the reconnect's spinner is up`() {
        assertEquals(
            R.string.iptv_guide_preview_reconnecting,
            GuidePreviewStatusPolicy.pillRes(LivePlaybackUiStatusCode.RECONNECTING, spinnerVisible = true),
        )
        assertEquals(R.string.iptv_guide_tuning, GuidePreviewStatusPolicy.pillRes(LivePlaybackUiStatusCode.STARTING, spinnerVisible = true))
        assertEquals("a buffering spinner reads as tuning, as before", R.string.iptv_guide_tuning,
            GuidePreviewStatusPolicy.pillRes(LivePlaybackUiStatusCode.BUFFERING, spinnerVisible = true))
        assertNull(GuidePreviewStatusPolicy.pillRes(null, spinnerVisible = false))
    }
}
