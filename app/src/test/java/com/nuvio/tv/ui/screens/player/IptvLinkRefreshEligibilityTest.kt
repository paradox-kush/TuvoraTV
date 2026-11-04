package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isIptvRefreshableHttpStatus] gates the one-shot fresh-create_link recovery: only token-shaped
 * stream failures qualify. 401/410 = expired/consumed play token, 403 = portal session rotated
 * (second device on the same MAC). Everything else must stay OUT — refreshing on 404 (content
 * removed) or 5xx (provider down) would burn the shot, and worse, hammering create_link on a
 * provider outage is how portals block MACs.
 */
class IptvLinkRefreshEligibilityTest {

    @Test
    fun `token-shaped statuses are refreshable`() {
        assertTrue(isIptvRefreshableHttpStatus(401))
        assertTrue(isIptvRefreshableHttpStatus(403))
        assertTrue(isIptvRefreshableHttpStatus(410))
    }

    @Test
    fun `content and provider failures are not refreshable`() {
        listOf(200, 204, 400, 404, 416, 429, 458, 500, 502, 503, 504).forEach { code ->
            assertFalse("HTTP $code must not trigger a link refresh", isIptvRefreshableHttpStatus(code))
        }
    }
}
