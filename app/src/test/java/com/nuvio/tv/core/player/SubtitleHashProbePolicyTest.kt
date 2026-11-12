package com.nuvio.tv.core.player

import org.junit.Assert.assertFalse
import com.nuvio.tv.core.contracts.OwnSourcePolicy
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SubtitleHashProbePolicyTest {
    private val notPanel: (String) -> Boolean = { false }

    @Before fun registerProviderScopedIds() {
        OwnSourcePolicy.resetForTest()
        OwnSourcePolicy.registerSubtitleScopedPredicate("test") { it.startsWith("xtream:") }
    }

    @After fun clearRegistry() = OwnSourcePolicy.resetForTest()

    @Test
    fun plainAddonStreamMayBeProbed() {
        assertTrue(
            "an addon stream is hashed as before",
            SubtitleHashProbePolicy.mayProbe("https://cdn.example/a.mkv", "tt1", "tt1", false, notPanel)
        )
    }

    @Test
    fun loopbackTorrentStreamMayBeProbed() {
        assertTrue(
            "torrent engine serves from loopback",
            SubtitleHashProbePolicy.mayProbe("http://127.0.0.1:11470/x", "tt1", "tt1", false, notPanel)
        )
    }

    @Test
    fun iptvPanelHostIsNeverProbed() {
        assertFalse(
            "a user's IPTV panel host must not be range-read for a hash",
            SubtitleHashProbePolicy.mayProbe("http://panel.example:8080/movie/u/p/1.mp4", "tt1", "tt1", false) { it == "panel.example" }
        )
    }

    @Test
    fun providerScopedIdsAreNeverProbed() {
        assertFalse(
            "xtream ids are provider scoped",
            SubtitleHashProbePolicy.mayProbe("https://cdn.example/a.mkv", "xtream:acct:movie:1", "xtream:acct:movie:1", false, notPanel)
        )
    }

    @Test
    fun liveContentIsNeverProbed() {
        assertFalse(
            "live feeds have no file to hash",
            SubtitleHashProbePolicy.mayProbe("https://cdn.example/live.ts", "tt1", "tt1", true, notPanel)
        )
    }

    @Test
    fun nonHttpOrMalformedUrlsAreNotProbed() {
        assertFalse("file scheme", SubtitleHashProbePolicy.mayProbe("file:///a.mkv", "tt1", "tt1", false, notPanel))
        assertFalse("garbage", SubtitleHashProbePolicy.mayProbe("not a url", "tt1", "tt1", false, notPanel))
        assertFalse("blank", SubtitleHashProbePolicy.mayProbe("", "tt1", "tt1", false, notPanel))
    }

    @Test
    fun liveContentTypeIsLivePlayback() {
        assertTrue("live type", SubtitleHashProbePolicy.isLivePlayback("live", false))
        assertTrue("case-insensitive", SubtitleHashProbePolicy.isLivePlayback("LIVE", false))
    }

    @Test
    fun catchUpReplayIsLivePlaybackEvenWhenTypeIsNotLive() {
        assertTrue(
            "a catch-up replay rides the live pipe and its link may be single-use",
            SubtitleHashProbePolicy.isLivePlayback("movie", true)
        )
    }

    @Test
    fun vodIsNotLivePlayback() {
        assertFalse("movie", SubtitleHashProbePolicy.isLivePlayback("movie", false))
        assertFalse("null type", SubtitleHashProbePolicy.isLivePlayback(null, false))
    }

    @Test
    fun catchUpReplayOfPlainHostIsNeverProbed() {
        val live = SubtitleHashProbePolicy.isLivePlayback("live", true)
        assertFalse(
            "catch-up replay must not spend a request on a hash",
            SubtitleHashProbePolicy.mayProbe("https://cdn.example/replay.ts", "m3u:abc", "m3u:abc", live, notPanel)
        )
    }

    @Test
    fun matchedIptvCopyUnderAPublicIdIsNeverProbed() {
        assertFalse(
            "B255: a TMDB-matched IPTV copy (public tt id, own-source stream label) must not be range-read",
            SubtitleHashProbePolicy.mayProbe(
                "https://cdn.example/a.mkv", "tt1", "tt1", false, notPanel, isMatchedIptvSource = true
            )
        )
    }

    @Test
    fun refreshableIptvIdIsNeverProbed() {
        assertFalse(
            "B255: an id the IPTV refresh lane owns must not be range-read",
            SubtitleHashProbePolicy.mayProbe(
                "https://cdn.example/a.mkv", "tt1", "tt1", false, notPanel, iptvId = "stalker:acct:1"
            )
        )
    }

    @Test
    fun ordinaryAddonStreamStillProbedWhenNotMatchedIptv() {
        assertTrue(
            "no iptv id and not a matched copy: hashed as before",
            SubtitleHashProbePolicy.mayProbe(
                "https://cdn.example/a.mkv", "tt1", "tt1", false, notPanel, iptvId = null, isMatchedIptvSource = false
            )
        )
    }
}
