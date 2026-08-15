package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.XtreamAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fix 1 — sticky provider selection. The hub used to reset to the first account on every fresh
 * entry (`selectedAccountId` starts null and fell straight to `accounts.firstOrNull()`); these pin
 * the restore policy: last-picked provider and section tab come back, but a provider that is gone
 * or disabled falls back to the first ENABLED account instead of resurrecting.
 */
class XtreamHubStickySelectionTest {

    private fun account(id: String, enabled: Boolean = true) = XtreamAccount(
        id = id,
        name = "Panel $id",
        baseUrl = "http://$id.example:8080",
        username = "u",
        password = "p",
        enabled = enabled,
    )

    @Test
    fun `the remembered provider is restored on entry`() {
        val accounts = listOf(account("a"), account("b"), account("c"))

        val selected = resolveStickyAccount(current = null, remembered = "b", accounts = accounts)

        assertEquals("a fresh entry must land on the remembered provider, not the first", "b", selected)
    }

    @Test
    fun `a removed account falls back to the first enabled`() {
        // The remembered playlist was deleted (or synced away) since the last visit.
        val accounts = listOf(account("a"), account("c"))

        val selected = resolveStickyAccount(current = null, remembered = "gone", accounts = accounts)

        assertEquals("a remembered id with no matching account must fall back", "a", selected)
    }

    @Test
    fun `a disabled account falls back to the first enabled`() {
        // The remembered playlist still exists but was toggled off — and so was the FIRST one,
        // so the fallback must be first ENABLED, not first overall.
        val accounts = listOf(account("a", enabled = false), account("b", enabled = false), account("c"))

        val selected = resolveStickyAccount(current = null, remembered = "b", accounts = accounts)

        assertEquals("a disabled remembered account must not be resurrected", "c", selected)
    }

    @Test
    fun `an in-session selection wins over the remembered provider`() {
        // Mid-session account-list refreshes re-run the resolver; the user's live pick stays.
        val accounts = listOf(account("a"), account("b"), account("c"))

        val selected = resolveStickyAccount(current = "c", remembered = "b", accounts = accounts)

        assertEquals("the in-memory selection must survive account-list re-emissions", "c", selected)
    }

    @Test
    fun `an in-session selection that vanished falls back to the first enabled`() {
        // The selected playlist was deleted while the hub was open.
        val accounts = listOf(account("a"), account("b"))

        val selected = resolveStickyAccount(current = "gone", remembered = "gone", accounts = accounts)

        assertEquals("a dead in-session selection must fall back like before the fix", "a", selected)
    }

    @Test
    fun `no enabled accounts leaves nothing selected`() {
        val accounts = listOf(account("a", enabled = false))

        assertNull(
            "with every playlist disabled there is nothing to select",
            resolveStickyAccount(current = null, remembered = "a", accounts = accounts)
        )
        assertNull(
            "an empty account list selects nothing",
            resolveStickyAccount(current = null, remembered = null, accounts = emptyList())
        )
    }

    @Test
    fun `the section tab is restored`() {
        val restored = resolveStickySection(remembered = "SERIES", fallback = XtreamSection.MOVIES)

        assertEquals("the last-used section tab must come back on entry", XtreamSection.SERIES, restored)
    }

    @Test
    fun `an unknown section name falls back to the default`() {
        // A name written by a newer build (or corrupted) reads as "nothing remembered", never a throw.
        assertEquals(
            "junk section names must fall back to the default tab",
            XtreamSection.MOVIES,
            resolveStickySection(remembered = "GARBAGE", fallback = XtreamSection.MOVIES)
        )
        assertEquals(
            "no remembered section means the default tab",
            XtreamSection.MOVIES,
            resolveStickySection(remembered = null, fallback = XtreamSection.MOVIES)
        )
    }
}
