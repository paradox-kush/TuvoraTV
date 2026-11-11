package com.nuvio.tv.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktRefreshTickPolicyTest {
    @Test fun `no Trakt account means no tick`() {
        assertFalse("B219: polling a signed-out account only logs a failure", TraktRefreshTickPolicy.shouldTick(accountConnected = false, appInForeground = true))
    }

    @Test fun `background means no tick`() {
        assertFalse("a poll nobody can see is still paid for", TraktRefreshTickPolicy.shouldTick(accountConnected = true, appInForeground = false))
    }

    @Test fun `no account and background means no tick`() {
        assertFalse("neither condition holds", TraktRefreshTickPolicy.shouldTick(accountConnected = false, appInForeground = false))
    }

    @Test fun `connected account in the foreground ticks`() {
        assertTrue("the only case that polls", TraktRefreshTickPolicy.shouldTick(accountConnected = true, appInForeground = true))
    }
}
