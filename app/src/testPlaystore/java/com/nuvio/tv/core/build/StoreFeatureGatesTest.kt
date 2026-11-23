package com.nuvio.tv.core.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Sync 17 store posture: built-in Usenet (GPL-3 sidecar + LAN setup server) and the audio subtitle
 * sync (speech model, unmeasured on 2 GB boxes) are compiled off in the Play build, like P2P.
 *
 * NOTE: JUnit argument order is assertX(message, actual) here.
 */
class StoreFeatureGatesTest {
    @Test fun `store flavor reports store`() = assertEquals("build_flavor", "store", AppFeaturePolicy.buildFlavor)

    @Test fun `store flavor has no built-in Usenet`() = assertFalse("usenetEnabled", AppFeaturePolicy.usenetEnabled)

    @Test fun `store flavor hides the audio subtitle sync`() =
        assertFalse("audioSubtitleSyncEnabled", AppFeaturePolicy.audioSubtitleSyncEnabled)

    @Test fun `store flavor keeps P2P off as before`() = assertFalse("p2pEnabled", AppFeaturePolicy.p2pEnabled)
}
