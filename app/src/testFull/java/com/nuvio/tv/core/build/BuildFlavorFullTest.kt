package com.nuvio.tv.core.build

import org.junit.Assert.assertEquals
import org.junit.Test

/** The `build_flavor` analytics value must match the phone/Desktop apps ("store" | "full"). */
class BuildFlavorFullTest {
    @Test
    fun `full flavor reports full`() =
        assertEquals("full flavor build_flavor", "full", AppFeaturePolicy.buildFlavor)
}

/** Sync 17: the GitHub (full) build ships built-in Usenet and the audio subtitle sync. */
class FullFeatureGatesTest {
    @Test
    fun `full flavor ships built-in Usenet and audio sync`() {
        org.junit.Assert.assertTrue("usenetEnabled", AppFeaturePolicy.usenetEnabled)
        org.junit.Assert.assertTrue("audioSubtitleSyncEnabled", AppFeaturePolicy.audioSubtitleSyncEnabled)
    }
}
