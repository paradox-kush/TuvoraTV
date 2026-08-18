package com.nuvio.tv.updater

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The banner is a Column sibling of the whole app (UpdateBannerHost), so while it is up it shrinks
 * the content below it — over the player that letterboxes the picture, and it leaves focusable
 * chrome (an Update button) one D-pad press above the video.
 *
 * The old code was `state.showBanner && update != null`, with no notion of the player at all, so
 * every case below expecting `false` for playerOnScreen would have returned `true`.
 *
 * NOTE: JUnit argument order here is assertEquals(message, expected, actual) — the opposite of
 * kotlin.test in the mobile twin. Do not regex-port between them.
 */
class UpdateBannerVisibilityPolicyTest {

    @After
    fun tearDown() {
        ImmersivePlaybackGate.resetForTest()
    }

    @Test
    fun `banner shows on a browse screen`() {
        assertTrue(
            "an update should still be announced when the player is not up",
            UpdateBannerVisibilityPolicy.mayOccupyLayout(
                hasUpdate = true,
                bannerRequested = true,
                playerOnScreen = false
            )
        )
    }

    @Test
    fun `banner stands down while the player owns the screen`() {
        assertFalse(
            "the banner shrinks the video and puts an Update button one D-pad press from playback",
            UpdateBannerVisibilityPolicy.mayOccupyLayout(
                hasUpdate = true,
                bannerRequested = true,
                playerOnScreen = true
            )
        )
    }

    @Test
    fun `no update or no request means no banner`() {
        assertFalse(
            "nothing to announce",
            UpdateBannerVisibilityPolicy.mayOccupyLayout(
                hasUpdate = false,
                bannerRequested = true,
                playerOnScreen = false
            )
        )
        assertFalse(
            "view model has not asked for the banner",
            UpdateBannerVisibilityPolicy.mayOccupyLayout(
                hasUpdate = true,
                bannerRequested = false,
                playerOnScreen = false
            )
        )
    }

    @Test
    fun `gate is reference counted across a player handoff`() {
        assertFalse("starts clear", ImmersivePlaybackGate.isActive.value)

        ImmersivePlaybackGate.setImmersive(true)
        ImmersivePlaybackGate.setImmersive(true)
        assertTrue("two players briefly composed", ImmersivePlaybackGate.isActive.value)

        ImmersivePlaybackGate.setImmersive(false)
        assertTrue(
            "the leaving screen must not clear a flag the arriving screen still needs",
            ImmersivePlaybackGate.isActive.value
        )

        ImmersivePlaybackGate.setImmersive(false)
        assertFalse("both gone", ImmersivePlaybackGate.isActive.value)
    }

    @Test
    fun `gate does not underflow`() {
        ImmersivePlaybackGate.setImmersive(false)
        ImmersivePlaybackGate.setImmersive(false)
        ImmersivePlaybackGate.setImmersive(true)
        assertEquals(
            "an unbalanced dispose must not drive the count negative and swallow the next enter",
            true,
            ImmersivePlaybackGate.isActive.value
        )
    }
}
