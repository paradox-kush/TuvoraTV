package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sync 15: upstream's expired-link refresh (e72921b68) re-fetches the playing source from its add-on
 * whenever the failed URL carries a token-like query key. Stalker links carry `play_token`, and a
 * Stalker mint returns a NEW url every time, so upstream's "each failed URL at most once" guard
 * never stops it: on a portal that keeps refusing, every fresh link would trigger another mint (the
 * stalker re-mint loop). IPTV links therefore stay with the fork's once-per-minute re-mint only, and
 * every source is capped URL-independently.
 *
 * NOTE: JUnit argument order is assertEquals(message, expected, actual) here.
 */
class ExpiredLinkRefreshOwnershipTest {

    private fun allowed(
        iptvId: String? = null,
        isCatchUp: Boolean = false,
        iptvRefreshAttempted: Boolean = false,
        isMatchedIptvSource: Boolean = false,
        consecutiveRefreshes: Int = 0,
    ) = ExpiredLinkRefreshOwnership.addonRefreshAllowed(
        iptvId = iptvId,
        isCatchUp = isCatchUp,
        iptvRefreshAttempted = iptvRefreshAttempted,
        isMatchedIptvSource = isMatchedIptvSource,
        consecutiveRefreshes = consecutiveRefreshes,
    )

    @Test
    fun `addon and debrid links keep upstream's refresh`() {
        assertTrue("a plain add-on stream with an expiring token is upstream's to refresh", allowed())
    }

    @Test
    fun `an Xtream or Stalker id never goes through the add-on refresh`() {
        assertFalse(
            "IPTV VOD and episodes are re-minted by IptvLinkRefreshPolicy, not re-fetched from an add-on",
            allowed(iptvId = "xtream:acc:vod:42")
        )
    }

    @Test
    fun `catch-up replays never go through the add-on refresh`() {
        assertFalse("a single-use catch-up link must not be replayed or re-minted by upstream's rung", allowed(isCatchUp = true))
    }

    @Test
    fun `a stream the IPTV re-mint already handled stays with it`() {
        assertFalse(
            "after the fork's re-mint declined (inside its re-arm window) upstream must not mint again",
            allowed(iptvRefreshAttempted = true)
        )
    }

    @Test
    fun `a matched Stalker link failing with a 5xx is not re-fetched by upstream`() {
        // The fork lane declines a 503 (provider outage: hammering create_link gets MACs blocked)...
        assertNull(
            "fork lane: a 5xx is not a refreshable status",
            IptvLinkRefreshPolicy.lane(
                httpStatus = 503, iptvId = null, isLiveId = false, isCatchUp = false,
                addonName = "My Stalker", alreadyAttempted = false,
            )
        )
        // ...so hasAttemptedIptvLinkRefresh stays false and the id is 'tt…': only the label says IPTV.
        assertFalse(
            "a TMDB-matched IPTV copy must never reach loadSourceStreams(forceRefresh = true): it mints a new link",
            allowed(isMatchedIptvSource = true)
        )
    }

    @Test
    fun `the refresh loop ends at the cap even with a new url every time`() {
        var consecutive = 0
        var refreshes = 0
        // Each failure carries a never-seen-before url, so the per-url guard never trips.
        repeat(50) {
            if (allowed(consecutiveRefreshes = consecutive)) {
                refreshes++
                consecutive++
            }
            // the fresh link dies at once: playback never advances past where it resumed
            consecutive = ExpiredLinkRefreshOwnership.consecutiveAfterPlayback(consecutive, positionMs = 600_000L, refreshBaselineMs = 600_000L)
        }
        assertEquals(
            "a source that keeps failing is refreshed at most the cap, then the error shows",
            ExpiredLinkRefreshOwnership.MAX_CONSECUTIVE_ADDON_REFRESHES,
            refreshes
        )
    }

    @Test
    fun `beyond the cap no refresh is allowed`() {
        assertFalse(
            "attempts beyond the cap are refused for any source",
            allowed(consecutiveRefreshes = ExpiredLinkRefreshOwnership.MAX_CONSECUTIVE_ADDON_REFRESHES)
        )
    }

    @Test
    fun `a refreshed link that kept playing re-arms the cap`() {
        assertEquals(
            "a minute of real playback past the refresh proves recovery (long movie, short-TTL link)",
            0,
            ExpiredLinkRefreshOwnership.consecutiveAfterPlayback(
                consecutiveRefreshes = 3,
                positionMs = 600_000L + ExpiredLinkRefreshOwnership.HEALTHY_PLAYBACK_RESET_MS,
                refreshBaselineMs = 600_000L,
            )
        )
        assertEquals(
            "a link that died at once keeps its count",
            2,
            ExpiredLinkRefreshOwnership.consecutiveAfterPlayback(2, positionMs = 605_000L, refreshBaselineMs = 600_000L)
        )
    }
}
