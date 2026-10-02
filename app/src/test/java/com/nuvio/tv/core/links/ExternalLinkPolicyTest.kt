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

    @Test
    fun `no resolvers means the link cannot open`() {
        assertEquals("canOpen", false, ExternalLinkPolicy.canOpen(emptyList()))
    }

    @Test
    fun `only the Android TV stub browser means the link cannot open`() {
        // Android TV's frameworkpackagestubs BrowserStub resolves VIEW https but only toasts
        // "You don't have an app that can do this" - it is not a browser.
        assertEquals(
            "canOpen",
            false,
            ExternalLinkPolicy.canOpen(listOf("com.android.tv.frameworkpackagestubs")),
        )
    }

    @Test
    fun `a real browser alongside the stub can open`() {
        assertEquals(
            "canOpen",
            true,
            ExternalLinkPolicy.canOpen(listOf("com.android.tv.frameworkpackagestubs", "com.android.chrome")),
        )
    }

    @Test
    fun `a real browser alone can open`() {
        assertEquals("canOpen", true, ExternalLinkPolicy.canOpen(listOf("org.mozilla.firefox")))
    }

    @Test
    fun `only https and mailto links are opened`() {
        listOf("https://tuvora.co/terms", "https://t.me/acme_tv", "https://wa.me/447700900123", "mailto:help@acme.tv").forEach {
            assertEquals(it, true, ExternalLinkPolicy.isAllowed(it))
        }
        listOf(
            "http://acme.tv", "javascript:alert(1)", "intent://x#Intent;end", "file:///etc/passwd", "tel:123", "market://details?id=x",
            "mailto:a@b.co?cc=x@y.z", "https://user:pw@acme.tv", "https://localhost", "https://intranet", "https://192.168.0.1",
            "https://127.0.0.1/", "https://2130706433", "https://[::1]/", "https://acme.tv/a b", "https://acme..tv",
        ).forEach { assertEquals(it, false, ExternalLinkPolicy.isAllowed(it)) }
    }

    @Test
    fun `a refused link launches nothing and shows no QR`() {
        val launched = mutableListOf<String>()
        assertEquals(ExternalLinkPolicy.Outcome.Refused, ExternalLinkPolicy.open("http://acme.tv", canResolve = true) { launched += it })
        assertEquals(ExternalLinkPolicy.Outcome.Refused, ExternalLinkPolicy.open("intent://x", canResolve = false) { launched += it })
        assertEquals(emptyList<String>(), launched)
    }

    @Test
    fun `an IDN host is shown as punycode`() {
        assertEquals("xn--bcher-kva.example", ExternalLinkPolicy.displayHost("https://b\u00FCcher.example/x"))
        assertEquals("https://xn--bcher-kva.example/x", ExternalLinkPolicy.safeHttpsUrl("https://b\u00FCcher.example/x"))
        assertEquals("acme.tv", ExternalLinkPolicy.displayHost("https://ACME.tv:8443/a?b#c"))
    }
}
