package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Security L4: where the code and the token may go; M6: store flavours do not receive add-ons. */
class ProviderSetupConfigTest {

    @Test
    fun `a base-url override is honoured only in a debug build`() {
        assertEquals("http://10.0.2.2:3000", ProviderSetupConfig.resolveBase("http://10.0.2.2:3000/", isDebug = true))
        assertEquals("a release build ignores it", "https://tuvora.co", ProviderSetupConfig.resolveBase("http://10.0.2.2:3000", isDebug = false))
        assertEquals("https://tuvora.co", ProviderSetupConfig.resolveBase("https://evil.example", isDebug = false))
        assertEquals("https://tuvora.co", ProviderSetupConfig.resolveBase("", isDebug = true))
    }

    @Test
    fun `the token goes only to the hosted backend over https (plain http only when debugging)`() {
        assertTrue(ProviderSetupConfig.sendsToken(true, "https://tuvora.co", isDebug = false))
        assertFalse("another backend", ProviderSetupConfig.sendsToken(false, "https://tuvora.co", isDebug = false))
        assertFalse("cleartext in release", ProviderSetupConfig.sendsToken(true, "http://10.0.0.5", isDebug = false))
        assertTrue("local test stack", ProviderSetupConfig.sendsToken(true, "http://10.0.2.2:3000", isDebug = true))
    }

    @Test
    fun `store flavours skip the package add-ons on redeem, full builds do not`() {
        assertTrue(RedeemAddonsPolicy.skipAddons(addonsEnabled = false))
        assertFalse(RedeemAddonsPolicy.skipAddons(addonsEnabled = true))
    }
}
