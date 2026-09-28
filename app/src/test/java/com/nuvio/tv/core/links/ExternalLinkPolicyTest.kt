package com.nuvio.tv.core.links

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Many Android TV devices ship no browser. On some images startActivity(ACTION_VIEW) then throws
 * ActivityNotFoundException; on others (the stock Android TV emulator) the system swallows the
 * intent and shows its own "You don't have an app that can do this" toast — a dead end. So
 * [ExternalLinkPolicy] decides BEFORE launching (does anything resolve the intent?) and keeps the
 * launch failure as a second safety net; either way the viewer gets the QR hand-off.
 */
class ExternalLinkPolicyTest {

    private val url = "https://tuvora.co/terms"

    /** Stand-in for android.content.ActivityNotFoundException (a RuntimeException). */
    private class NoActivityFound : RuntimeException("No Activity found to handle Intent")

    @Test
    fun `browser available opens the link`() {
        val launched = mutableListOf<String>()
        val outcome = ExternalLinkPolicy.open(url, canResolve = true) { launched += it }
        assertEquals("outcome", ExternalLinkPolicy.Outcome.Opened, outcome)
        assertEquals("launched url", listOf(url), launched)
    }

    @Test
    fun `nothing resolves shows the QR without launching`() {
        val launched = mutableListOf<String>()
        val outcome = ExternalLinkPolicy.open(url, canResolve = false) { launched += it }
        assertEquals("outcome", ExternalLinkPolicy.Outcome.ShowQr(url), outcome)
        assertEquals("must not hand the intent to the system (it shows a dead-end toast)", emptyList<String>(), launched)
    }

    @Test
    fun `resolved but launch throws still falls back to QR`() {
        val outcome = ExternalLinkPolicy.open(url, canResolve = true) { throw NoActivityFound() }
        assertEquals("outcome", ExternalLinkPolicy.Outcome.ShowQr(url), outcome)
    }

    @Test
    fun `a browser that refuses the intent also falls back to QR`() {
        val outcome = ExternalLinkPolicy.open(url, canResolve = true) { throw SecurityException("Permission Denial") }
        assertEquals("outcome", ExternalLinkPolicy.Outcome.ShowQr(url), outcome)
    }
}
