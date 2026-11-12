package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sync 15: upstream's expired-link refresh (e72921b68) re-fetches the playing source from its add-on
 * whenever the failed URL carries a token-like query key. Stalker links carry `play_token`, and a
 * Stalker mint returns a NEW url every time, so upstream's "each failed URL at most once" guard
 * never stops it: on a portal that keeps refusing, every fresh link would trigger another mint (the
 * stalker re-mint loop). IPTV links therefore stay with the fork's once-per-minute re-mint only.
 *
 * NOTE: JUnit argument order is assertEquals(message, expected, actual) here.
 */
class ExpiredLinkRefreshOwnershipTest {

    @Test
    fun `addon and debrid links keep upstream's refresh`() {
        assertTrue(
            "a plain add-on stream with an expiring token is upstream's to refresh",
            ExpiredLinkRefreshOwnership.addonRefreshAllowed(iptvId = null, isCatchUp = false, iptvRefreshAttempted = false)
        )
    }

    @Test
    fun `an Xtream or Stalker id never goes through the add-on refresh`() {
        assertFalse(
            "IPTV VOD and episodes are re-minted by IptvLinkRefreshPolicy, not re-fetched from an add-on",
            ExpiredLinkRefreshOwnership.addonRefreshAllowed(iptvId = "xtream:acc:vod:42", isCatchUp = false, iptvRefreshAttempted = false)
        )
    }

    @Test
    fun `catch-up replays never go through the add-on refresh`() {
        assertFalse(
            "a single-use catch-up link must not be replayed or re-minted by upstream's rung",
            ExpiredLinkRefreshOwnership.addonRefreshAllowed(iptvId = null, isCatchUp = true, iptvRefreshAttempted = false)
        )
    }

    @Test
    fun `a stream the IPTV re-mint already handled stays with it`() {
        assertFalse(
            "after the fork's re-mint declined (inside its re-arm window) upstream must not mint again",
            ExpiredLinkRefreshOwnership.addonRefreshAllowed(iptvId = null, isCatchUp = false, iptvRefreshAttempted = true)
        )
    }
}
