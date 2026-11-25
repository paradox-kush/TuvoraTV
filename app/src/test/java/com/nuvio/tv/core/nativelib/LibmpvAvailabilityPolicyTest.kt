package com.nuvio.tv.core.nativelib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B287: a device whose libmpv will not load must get ExoPlayer, not a crash in mpv's constructor. Twin: NuvioMobile. */
class LibmpvAvailabilityPolicyTest {

    @Test
    fun libmpvWantedButNotLoadableFallsBack() {
        assertFalse("a failed native load is not usable", LibmpvAvailabilityPolicy.usable(wanted = true) { false })
    }

    @Test
    fun libmpvWantedAndLoadableIsUsed() {
        assertTrue("loadable libmpv is used", LibmpvAvailabilityPolicy.usable(wanted = true) { true })
    }

    @Test
    fun probeIsNotRunWhenLibmpvIsNotWanted() {
        var probed = 0
        assertFalse("ExoPlayer is not libmpv", LibmpvAvailabilityPolicy.usable(wanted = false) { probed++; true })
        assertEquals("ExoPlayer playback never loads mpv's native code", 0, probed)
    }
}
