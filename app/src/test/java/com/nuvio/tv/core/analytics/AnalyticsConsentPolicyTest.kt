package com.nuvio.tv.core.analytics

import com.nuvio.tv.core.analytics.AnalyticsConsentPolicy.Action.OPT_IN
import com.nuvio.tv.core.analytics.AnalyticsConsentPolicy.Action.OPT_OUT
import com.nuvio.tv.core.analytics.AnalyticsConsentPolicy.Action.REPORT_PENDING_EXITS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Consent is read off Main now, so PostHog starts before the stored value is known. Privacy
 * contract: nothing is sent until that value arrives, and a stored opt-out is never overridden.
 */
class AnalyticsConsentPolicyTest {

    @Test
    fun `nothing is sent before the stored consent is known`() {
        val policy = AnalyticsConsentPolicy()
        assertTrue("the SDK must be set up opted out", AnalyticsConsentPolicy.SDK_STARTS_OPTED_OUT)
        assertFalse("consent is unknown until the first stored value", policy.consentKnown)
    }

    @Test
    fun `stored opt-out stays opted out and reports nothing`() {
        val policy = AnalyticsConsentPolicy()
        assertEquals("first value false", listOf(OPT_OUT), policy.onStoredConsent(false))
        assertTrue("consent known after the first value", policy.consentKnown)
        assertEquals("a repeated false never opts in", listOf(OPT_OUT), policy.onStoredConsent(false))
    }

    @Test
    fun `stored opt-in opts in and reports pending exits exactly once`() {
        val policy = AnalyticsConsentPolicy()
        assertEquals("first value true", listOf(OPT_IN, REPORT_PENDING_EXITS), policy.onStoredConsent(true))
        assertEquals("later false", listOf(OPT_OUT), policy.onStoredConsent(false))
        assertEquals("later true opts back in without a second report", listOf(OPT_IN), policy.onStoredConsent(true))
    }

    @Test
    fun `later toggles after a stored opt-out apply without start-up reports`() {
        val policy = AnalyticsConsentPolicy()
        policy.onStoredConsent(false)
        assertEquals("user turns diagnostics on later", listOf(OPT_IN), policy.onStoredConsent(true))
        assertEquals("and off again", listOf(OPT_OUT), policy.onStoredConsent(false))
    }
}
