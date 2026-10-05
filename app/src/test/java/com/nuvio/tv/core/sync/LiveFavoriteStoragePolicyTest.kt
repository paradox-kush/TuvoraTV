package com.nuvio.tv.core.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveFavoriteStoragePolicyTest {
    @Test
    fun `a live favourite stays local under Trakt`() {
        assertTrue("live channel", LiveFavoriteStoragePolicy.storesLocally("xtream:http://h|u:live:7", "tv"))
        assertFalse("a movie follows the library source", LiveFavoriteStoragePolicy.storesLocally("xtream:http://h|u:vod:7", "movie"))
        assertFalse("a non-IPTV item follows the library source", LiveFavoriteStoragePolicy.storesLocally("tt0111161", "movie"))
    }

    @Test
    fun `the Tuvora library is pulled under a tracking provider`() {
        assertTrue(LiveFavoriteStoragePolicy.pullsNuvioLibrary(trackingProviderActive = true))
    }
}
