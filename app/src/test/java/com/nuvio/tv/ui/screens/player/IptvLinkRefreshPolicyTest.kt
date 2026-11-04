package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which lane (if any) re-mints an expired IPTV link on upstream's VOD player. One shot per stream;
 * never for catch-up (its own dialect walk owns its errors) or a live id (live left this player).
 */
class IptvLinkRefreshPolicyTest {
    private fun lane(
        status: Int? = 401,
        iptvId: String? = "xtream:acc:vod:42",
        isLiveId: Boolean = false,
        isCatchUp: Boolean = false,
        addonName: String? = null,
        attempted: Boolean = false,
    ) = IptvLinkRefreshPolicy.lane(status, iptvId, isLiveId, isCatchUp, addonName, attempted)

    @Test
    fun anIptvVodIdWithATokenStatusIsReMintedById() {
        for (code in listOf(401, 403, 410)) {
            assertEquals("HTTP $code", IptvLinkRefreshPolicy.Lane.IptvId("xtream:acc:vod:42"), lane(status = code))
        }
    }

    @Test
    fun aMatchedCopyIsReMintedThroughItsAccountLabel() {
        assertEquals(
            "tmdb-matched IPTV stream carries the account name",
            IptvLinkRefreshPolicy.Lane.Matched,
            lane(iptvId = null, addonName = "My Panel")
        )
        assertNull("no label: nothing to re-match against", lane(iptvId = null, addonName = " "))
    }

    @Test
    fun nothingElseSpendsTheShot() {
        assertNull("no HTTP status", lane(status = null))
        assertNull("404 removed", lane(status = 404))
        assertNull("5xx provider down", lane(status = 503))
        assertNull("one shot only", lane(attempted = true))
        assertNull("catch-up has its own walk", lane(isCatchUp = true))
        assertNull("live left this player", lane(isLiveId = true))
    }
}
