package com.nuvio.tv.core.build

import org.junit.Assert.assertEquals
import org.junit.Test

/** The `build_flavor` analytics value must match the phone/Desktop apps ("store" | "full"). */
class BuildFlavorFullTest {
    @Test
    fun `full flavor reports full`() =
        assertEquals("full flavor build_flavor", "full", AppFeaturePolicy.buildFlavor)
}
