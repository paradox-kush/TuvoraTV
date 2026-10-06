package com.nuvio.tv.core.streams

import com.nuvio.tv.core.iptv.PlaylistKey
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.iptv.tvLegacyM3uId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Store builds play only IPTV, never add-on streams ([AddonSourcePolicy]). On TV, IPTV-ness is decided
 * from the CONTENT id ([PlaybackAvailability.isIptvId]), and since Step 0.2 the account segment of that
 * id is the frozen playlist key (`m3u|…`, `stalker|…`, `m3u_file|…`, possibly with a `#n` suffix) or a
 * legacy derived id. Every shape must still be recognised, or store builds would drop IPTV embedded
 * streams and refuse IPTV cached links. URLs (which Step 0.3 failover may rebase onto a backup host)
 * play no part in the decision.
 */
class StorePostureIptvRecognitionTest {
    // IPTV's "own source" ids are registered policy now: wire it as NuvioApplication does.
    @Before
    fun wireIptv() {
        com.nuvio.tv.core.contracts.OwnSourcePolicy.resetForTest()
        com.nuvio.tv.core.iptv.IptvSourceRegistrations.register()
    }

    @After
    fun unwireIptv() = com.nuvio.tv.core.contracts.OwnSourcePolicy.resetForTest()

    private val accountIds: List<String> = listOfNotNull(
        PlaylistKey.xtream("http://Panel.Example.com:80/player_api.php", "alice"),
        PlaylistKey.m3uUrl("panel.example.com/get.php?username=a&password=b&type=m3u_plus"),
        PlaylistKey.stalker("http://portal.example.com:8080/c/", "00:1a:79:aa:bb:cc"),
        PlaylistKey.m3uFile("my list.m3u", 1_727_740_800_000L),
        tvLegacyM3uId("http://panel.example.com/list.m3u"),
    ).let { ids -> ids + ids.map { "$it#2" } }

    private fun contentIds(accountId: String) = listOf(
        XtreamItemRegistry.vodId(accountId, 42),
        XtreamItemRegistry.seriesId(accountId, 7),
        XtreamItemRegistry.episodeId(accountId, "1001"),
        XtreamItemRegistry.liveId(accountId, 3),
    )

    @Test
    fun `every playlist key shape is built`() {
        assertEquals("4 Step 0.2 key shapes + legacy M3U id, each also with a #n suffix", 10, accountIds.size)
    }

    @Test
    fun `store build recognises IPTV content for every playlist key shape`() {
        for (accountId in accountIds) {
            for (id in contentIds(accountId)) {
                val isIptv = PlaybackAvailability.isIptvId(id)
                assertTrue("IPTV content id $id is recognised", isIptv)
                assertTrue(
                    "store build replays the cached IPTV link for $id",
                    AddonSourcePolicy.cachedLinkUsable(streamSourcesEnabled = false, isIptv = isIptv)
                )
            }
        }
    }

    @Test
    fun `store build still treats add-on content ids as add-on content`() {
        for (id in listOf("tt0111161", "tt0944947:1:1", "kitsu:1", "tmdb:603")) {
            val isIptv = PlaybackAvailability.isIptvId(id)
            assertFalse("$id is not IPTV", isIptv)
            assertFalse(
                "store build never replays a cached link for add-on content $id",
                AddonSourcePolicy.cachedLinkUsable(streamSourcesEnabled = false, isIptv = isIptv)
            )
        }
    }
}
