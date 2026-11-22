package com.nuvio.tv.core.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupConsentPolicyTest {

    @Test
    fun `missing consent answer starts opted out and defers exit report`() {
        val c = StartupConsentPolicy.initial(null)
        assertTrue("nothing may be sent before consent is known", c.optOut)
        assertFalse("pending answer is not known", c.known)
        assertFalse("applyConsent(false) wipes a consenting user's trail; must wait", c.applyConsentNow)
        assertFalse("exit report / crash send wait for a confirmed yes", c.runConsentedWorkNow)
        assertFalse("held funnel events would be dropped at delivery; keep holding", c.releaseFunnelNow)
    }

    @Test
    fun `consent true starts opted in`() {
        assertEquals(
            "a known yes behaves exactly as before the change",
            StartupConsentPolicy.InitialConsent(
                optOut = false, known = true, applyConsentNow = true, runConsentedWorkNow = true, releaseFunnelNow = true,
            ),
            StartupConsentPolicy.initial(true),
        )
    }

    @Test
    fun `consent false starts opted out and applies the opt-out`() {
        assertEquals(
            "a known no behaves exactly as before the change",
            StartupConsentPolicy.InitialConsent(
                optOut = true, known = true, applyConsentNow = true, runConsentedWorkNow = false, releaseFunnelNow = true,
            ),
            StartupConsentPolicy.initial(false),
        )
    }

    @Test
    fun `late yes after an unresolved start runs the deferred exit report once`() {
        val initial = StartupConsentPolicy.initial(null)
        assertEquals(
            "first answer yes: late, not lost",
            StartupConsentPolicy.LateAnswer(runConsentedWork = true, releaseFunnel = true),
            StartupConsentPolicy.onAnswer(initial, enabled = true, deferredSettled = false),
        )
        assertEquals(
            "a later toggle never repeats the startup work",
            StartupConsentPolicy.LateAnswer(runConsentedWork = false, releaseFunnel = false),
            StartupConsentPolicy.onAnswer(initial, enabled = true, deferredSettled = true),
        )
    }

    @Test
    fun `late no after an unresolved start releases the funnel but sends nothing`() {
        assertEquals(
            "funnel delivery checks consent itself and drops; no exit report",
            StartupConsentPolicy.LateAnswer(runConsentedWork = false, releaseFunnel = true),
            StartupConsentPolicy.onAnswer(StartupConsentPolicy.initial(null), enabled = false, deferredSettled = false),
        )
    }

    @Test
    fun `answers after a known start trigger no extra startup work`() {
        val none = StartupConsentPolicy.LateAnswer(runConsentedWork = false, releaseFunnel = false)
        assertEquals("known yes", none, StartupConsentPolicy.onAnswer(StartupConsentPolicy.initial(true), true, false))
        assertEquals("known no, later yes", none, StartupConsentPolicy.onAnswer(StartupConsentPolicy.initial(false), true, false))
    }
}
