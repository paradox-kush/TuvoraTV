package com.nuvio.tv.core.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsConsentPolicyTest {

    /**
     * The gate shipped in TV v1.4.19 / mobile v1.4.20 defaulted to off, and because it drives
     * PostHog's opt-out it silenced every event, not only crashes. Measured two weeks later: 416
     * reporting users were on builds from before the gate and 11 on builds after it, so the app went
     * effectively blind as people updated.
     */
    @Test
    fun `an untouched install reports by default`() {
        assertTrue(resolveDiagnosticsEnabled(stored = null))
    }

    /** An explicit no is a decision, and survives the change of default. */
    @Test
    fun `an explicit opt-out is preserved`() {
        assertFalse(resolveDiagnosticsEnabled(stored = false))
    }

    @Test
    fun `an explicit opt-in is preserved`() {
        assertTrue(resolveDiagnosticsEnabled(stored = true))
    }

    /** Only an absent value may be defaulted; a stored value is authoritative either way. */
    @Test
    fun `a stored value always wins over the default`() {
        assertEquals(false, resolveDiagnosticsEnabled(stored = false))
        assertEquals(true, resolveDiagnosticsEnabled(stored = true))
    }
}
