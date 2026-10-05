package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Device pass 2026-10-05 (emulator): saving the Edit playlist form turned "Clean up channel names"
 * back Off. The form rebuilds the account from its own fields and [asEditOf] carried only identity,
 * enabled, content types, category selections and backups — every device-local preference set from
 * the playlist's cards (F10 clean-up + tags, F35 prefer m3u8, catch-up and guide offsets) was reset.
 */
class PlaylistEditKeepsDevicePrefsTest {

    private val old = XtreamAccount(
        id = "m3u|http://h/p.m3u", name = "Old", baseUrl = "http://h/p.m3u", username = "", password = "",
        sourceType = XtreamAccount.SOURCE_URL,
        preferM3u8CatchUp = true, catchUpCorrectionMinutes = -60, guideEpgCorrectionMinutes = 30,
        cleanChannelNames = true, channelNameTags = "VIP",
    )

    @Test
    fun `an edit keeps the playlist's device-local preferences`() {
        val formRebuild = XtreamAccount(
            id = "ignored", name = "Renamed", baseUrl = "http://h/p.m3u", username = "", password = "",
            sourceType = XtreamAccount.SOURCE_URL, epgUrl = "http://h/epg.xml",
        )
        val saved = formRebuild.asEditOf(old)
        assertEquals("prefer m3u8", true, saved.preferM3u8CatchUp)
        assertEquals("catch-up correction", -60, saved.catchUpCorrectionMinutes)
        assertEquals("guide offset", 30, saved.guideEpgCorrectionMinutes)
        assertEquals("clean-up", true, saved.cleanChannelNames)
        assertEquals("tags", "VIP", saved.channelNameTags)
        assertEquals("the form's own fields still win", "Renamed", saved.name)
        assertEquals("the form's own fields still win", "http://h/epg.xml", saved.epgUrl)
    }
}
