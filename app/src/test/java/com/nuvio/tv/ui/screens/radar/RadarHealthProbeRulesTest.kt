package com.nuvio.tv.ui.screens.radar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The match sheet health-probes a channel before playing it, to skip the offline channels IPTV
 * panels routinely keep listed. That probe reads a byte off the stream, which is safe only for
 * sources whose URL is reusable.
 */
class RadarHealthProbeRulesTest {

    @Test
    fun `channels that list a reusable url are probed`() {
        // Xtream: formula-derived player_api URL.
        assertTrue(radarChannelNeedsHealthProbe("http://panel.example:8080/live/user/pass/1.ts"))
        // M3U: the URL straight out of the ingested playlist.
        assertTrue(radarChannelNeedsHealthProbe("http://cdn.example/playlist/2.m3u8"))
    }

    @Test
    fun `channels that list no url are not probed`() {
        // Stalker lists a blank URL and mints a single-use create_link per play — probing it
        // would burn the very link the player is about to open.
        assertFalse(radarChannelNeedsHealthProbe(""))
    }
}
