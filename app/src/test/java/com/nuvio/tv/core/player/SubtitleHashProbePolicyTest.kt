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
}
