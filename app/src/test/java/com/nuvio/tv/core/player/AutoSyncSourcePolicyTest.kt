package com.nuvio.tv.core.player

import com.nuvio.tv.core.contracts.OwnSourcePolicy
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Sync 15: upstream's AutoSync (default ON) range-reads the STREAM url to index embedded subtitles.
 * On an IPTV / own-source / catch-up / live link that read can burn a single-use Stalker play_token
 * or take the one connection slot the player needs, so only plain add-on/debrid streams are probed.
 *
 * NOTE: JUnit argument order is assertX(message, actual) here.
 */
class AutoSyncSourcePolicyTest {
    private val notPanel: (String) -> Boolean = { false }

    @Before fun registerOwnSources() {
        OwnSourcePolicy.resetForTest()
        OwnSourcePolicy.registerContentIdPredicate("test-iptv") { it.startsWith("xtream:") || it.startsWith("m3u:") }
        OwnSourcePolicy.registerContentIdPredicate("test-ms") { it.startsWith("ms:") }
        OwnSourcePolicy.registerSubtitleScopedPredicate("test-iptv") { it.startsWith("xtream:") }
    }

    @After fun clearRegistry() = OwnSourcePolicy.resetForTest()

    private fun mayProbe(
        url: String = "https://cdn.example/movie.mkv?token=abc",
        iptvId: String? = null,
        contentId: String? = "tt0111161",
        videoId: String? = "tt0111161",
        contentType: String? = "movie",
        isCatchUp: Boolean = false,
        isMatchedIptvSource: Boolean = false,
        isPanelHost: (String) -> Boolean = notPanel,
    ) = AutoSyncSourcePolicy.mayProbe(url, iptvId, contentId, videoId, contentType, isCatchUp, isMatchedIptvSource, isPanelHost)

    @Test
    fun `a plain add-on stream may be probed`() {
        assertTrue("an add-on/debrid stream keeps upstream's AutoSync", mayProbe())
    }

    @Test
    fun `a Stalker vod id is never probed`() {
        assertFalse(
            "a Stalker play_token link is single-use: the probe would burn it",
            mayProbe(
                url = "http://portal.example/play/movie.php?stream=1.mkv&play_token=XYZ",
                iptvId = "xtream:mac:vod:42",
                contentId = "xtream:mac:vod:42",
                videoId = "xtream:mac:vod:42",
            )
        )
    }

    @Test
    fun `an Xtream episode id is never probed`() {
        assertFalse(
            "an Xtream panel may allow one connection: the probe takes the player's slot",
            mayProbe(
                url = "http://other-cdn.example/series/u/p/9.mkv",
                iptvId = "xtream:acc:series:9",
                contentId = "xtream:acc:series:1",
                videoId = "xtream:acc:series:9",
                contentType = "series",
            )
        )
    }

    @Test
    fun `an M3U id is never probed even when no classifier answered`() {
        assertFalse("own-source content id", mayProbe(iptvId = null, contentId = "m3u:list:7", videoId = "m3u:list:7"))
    }

    @Test
    fun `a media-server item is never probed`() {
        assertFalse("own-source media server id", mayProbe(contentId = "ms:srv:1", videoId = "ms:srv:1"))
    }

    @Test
    fun `a TMDB-matched IPTV copy is never probed`() {
        assertFalse(
            "a matched Stalker copy plays under a tt id but is still a single-use IPTV link",
            mayProbe(
                url = "http://portal.example/play/movie.php?play_token=XYZ",
                isMatchedIptvSource = true,
            )
        )
    }

    @Test
    fun `a stream on an IPTV panel host is never probed`() {
        assertFalse(
            "a panel host the IPTV lane handed out is never range-read",
            mayProbe(url = "http://panel.example:8080/movie/u/p/1.mkv") { it == "panel.example" }
        )
    }

    @Test
    fun `catch-up is never probed`() {
        assertFalse("a catch-up replay link is single-use", mayProbe(contentType = "movie", isCatchUp = true))
    }

    @Test
    fun `live is never probed`() {
        assertFalse("a live feed has no index and one connection", mayProbe(contentType = "live"))
    }

    @Test
    fun `a non-http url is never probed`() {
        assertFalse("nothing to range-read", mayProbe(url = "torrent://abc"))
    }
}
