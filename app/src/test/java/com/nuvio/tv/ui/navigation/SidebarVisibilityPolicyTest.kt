package com.nuvio.tv.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the nav rail sitting on top of a fullscreen live channel.
 *
 * Adding XtreamHub/SportsHub to rootRoutes gave those screens the rail, LEFT-to-open and
 * BACK-to-sidebar — but the live guide goes fullscreen *inside* the hub route, so the old
 * `currentRoute in rootRoutes` had no way to know the player had taken the screen. Caught on an
 * Onn 4K (2026-08-18): the sidebar icons stayed painted over a fullscreen channel.
 *
 * The fullscreen-live case below is the one that regressed: under the old rule it returned true.
 *
 * NOTE: JUnit argument order is assertEquals(message, expected, actual) here — the opposite of
 * kotlin.test in the mobile/desktop twins. Do not regex-port between them.
 */
class SidebarVisibilityPolicyTest {

    @Test
    fun `rail shows while browsing a root screen`() {
        assertTrue(
            "the hub is a root destination and nothing owns the screen",
            SidebarVisibilityPolicy.showSidebar(routeHasSidebar = true, immersivePlayback = false)
        )
    }

    @Test
    fun `rail stands down when a player owns the screen on a root route`() {
        assertFalse(
            "a fullscreen live channel on the IPTV hub must not keep the nav rail on top of it",
            SidebarVisibilityPolicy.showSidebar(routeHasSidebar = true, immersivePlayback = true)
        )
    }

    @Test
    fun `rail stays away on a non-root route`() {
        assertFalse(
            "detail and player routes never carry the rail",
            SidebarVisibilityPolicy.showSidebar(routeHasSidebar = false, immersivePlayback = false)
        )
    }

    @Test
    fun `a non-root route with a player on screen still has no rail`() {
        assertFalse(
            "neither condition is met",
            SidebarVisibilityPolicy.showSidebar(routeHasSidebar = false, immersivePlayback = true)
        )
    }
}
