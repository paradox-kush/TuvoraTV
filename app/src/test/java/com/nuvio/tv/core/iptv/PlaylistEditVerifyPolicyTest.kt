package com.nuvio.tv.core.iptv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B60 decision 1 (2026-09-27): a failed provider check on an edit saves anyway and warns. */
class PlaylistEditVerifyPolicyTest {

    private fun acc() = XtreamAccount(id = "http://A|u", name = "P", baseUrl = "http://A", username = "u", password = "p")

    @Test
    fun `an options-only edit needs no provider check`() {
        assertFalse(PlaylistEditVerifyPolicy.needsVerify(acc(), acc().copy(name = "Renamed", autoRefreshHours = 6, userAgent = "UA")))
    }

    @Test
    fun `a URL edit needs a provider check`() {
        assertTrue(PlaylistEditVerifyPolicy.needsVerify(acc(), acc().copy(id = "http://B|u", baseUrl = "http://B")))
    }

    @Test
    fun `a failed provider check on a URL edit still saves - with a warning`() {
        val outcome = PlaylistEditVerifyPolicy.outcome(Result.failure(RuntimeException("Could not reach the panel")))
        assertTrue("what the user typed is never discarded", outcome.save)
        assertNotNull("the failure is surfaced, not swallowed", outcome.warning)
        assertTrue(outcome.warning!!, outcome.warning!!.contains("Could not reach the panel"))
    }

    @Test
    fun `a passed provider check saves with no warning`() {
        val outcome = PlaylistEditVerifyPolicy.outcome(Result.success(Unit))
        assertTrue(outcome.save)
        assertNull(outcome.warning)
    }
}
