package com.nuvio.tv.core.links

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Many Android TV devices ship no browser, so ACTION_VIEW throws ActivityNotFoundException.
 * [ExternalLinkPolicy] tries the browser and, when nothing can take the link, falls back to the
 * QR hand-off so the viewer can still read the page on their phone — never a crash.
 */
class ExternalLinkPolicyTest {

    private val url = "https://tuvora.co/terms"

    /** Stand-in for android.content.ActivityNotFoundException (a RuntimeException). */
    private class NoActivityFound : RuntimeException("No Activity found to handle Intent")

    @Test
    fun `browser available opens the link`() {
        val launched = mutableListOf<String>()
        val outcome = ExternalLinkPolicy.open(url) { launched += it }
        assertEquals("outcome", ExternalLinkPolicy.Outcome.Opened, outcome)
        assertEquals("launched url", listOf(url), launched)
    }

    @Test
    fun `no browser falls back to the QR hand-off instead of crashing`() {
        val outcome = ExternalLinkPolicy.open(url) { throw NoActivityFound() }
        assertEquals("outcome", ExternalLinkPolicy.Outcome.ShowQr(url), outcome)
    }

    @Test
    fun `a browser that refuses the intent also falls back to QR`() {
        val outcome = ExternalLinkPolicy.open(url) { throw SecurityException("Permission Denial") }
        assertEquals("outcome", ExternalLinkPolicy.Outcome.ShowQr(url), outcome)
    }
}
