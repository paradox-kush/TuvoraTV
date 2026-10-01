package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** UX15: the fingerprint changes exactly when what IPTV search can return changes. */
class IptvSearchSourceSignatureTest {

    private val a = XtreamAccount(id = "http://a.test", name = "A", baseUrl = "http://a.test", username = "u", password = "p")
    private val b = XtreamAccount(id = "http://b.test", name = "B", baseUrl = "http://b.test", username = "u", password = "p")

    private fun sig(vararg accounts: XtreamAccount) = XtreamIptvSearchProvider.signatureOf(accounts.toList())

    @Test
    fun `no enabled playlist has no signature`() {
        assertNull("none", sig())
        assertNull("only disabled", sig(a.copy(enabled = false)))
        assertNotNull("one enabled", sig(a))
    }

    @Test
    fun `content settings that change results change the signature`() {
        val base = sig(a, b)
        assertNotEquals("movies switched off", base, sig(a.copy(contentTypes = a.contentTypes - XtreamAccount.TYPE_MOVIES), b))
        assertNotEquals("category selection narrowed", base,
            sig(a.copy(categorySelections = CategorySelections(live = listOf("12"))), b))
        assertNotEquals("deselect all differs from all", sig(a.copy(categorySelections = CategorySelections(live = null))),
            sig(a.copy(categorySelections = CategorySelections(live = emptyList()))))
        assertNotEquals("playlist disabled", base, sig(a, b.copy(enabled = false)))
        assertNotEquals("playlist added", sig(a), base)
        assertNotEquals("credentials re-pointed", base, sig(a.copy(username = "other"), b))
    }

    @Test
    fun `settings that cannot change results keep the signature`() {
        val base = sig(a, b)
        assertEquals("playlist order", base, sig(b, a))
        assertEquals("rename", base, sig(a.copy(name = "Renamed"), b))
        assertEquals("epg / catch-up / user agent", base,
            sig(a.copy(epgUrl = "http://epg.test", catchUpCorrectionMinutes = 60, userAgent = "UA"), b))
        assertEquals("category selection order", sig(a.copy(categorySelections = CategorySelections(movies = listOf("1", "2")))),
            sig(a.copy(categorySelections = CategorySelections(movies = listOf("2", "1")))))
    }

    @Test
    fun `the signature never carries the password`() {
        val s = sig(a.copy(password = "s3cret-pass"))!!
        assertEquals("no password text", false, s.contains("s3cret-pass"))
    }
}
