package com.nuvio.tv.core.sports.detail.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelLookupPolicyTest {
    @Test fun `a cold start still downloading a playlist gets one more attempt`() {
        assertTrue(ChannelLookupPolicy.retryAfterTimeout(matchesSoFar = 0, lineupsDownloading = 1))
    }

    @Test fun `a slow lookup with every lineup ready is not repeated`() {
        assertFalse(ChannelLookupPolicy.retryAfterTimeout(matchesSoFar = 0, lineupsDownloading = 0))
    }

    @Test fun `matches already found are kept and never re-asked`() {
        assertFalse(ChannelLookupPolicy.retryAfterTimeout(matchesSoFar = 3, lineupsDownloading = 2))
    }
}
