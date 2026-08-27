package com.nuvio.tv.ui.screens.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanPlaybackSettingsDebugRouteTest {
    @Test
    fun `clean playback route is visible only for debug artifacts`() {
        assertTrue(CleanPlaybackSettingsDebugRoute.isVisible(buildType = "debug"))
        assertFalse(CleanPlaybackSettingsDebugRoute.isVisible(buildType = "release"))
        assertFalse(CleanPlaybackSettingsDebugRoute.isVisible(buildType = "benchmark"))
    }

    @Test
    fun `debug destination resolves on the debug test classpath`() {
        assertTrue(
            Class.forName(CleanPlaybackSettingsDebugRoute.DESTINATION_CLASS_NAME).name ==
                CleanPlaybackSettingsDebugRoute.DESTINATION_CLASS_NAME,
        )
    }
}
