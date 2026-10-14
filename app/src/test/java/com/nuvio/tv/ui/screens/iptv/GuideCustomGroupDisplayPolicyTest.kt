package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.overlay.ChannelOverlay
import org.junit.Assert.assertEquals
import org.junit.Test

class GuideCustomGroupDisplayPolicyTest {
    private val raw = listOf(
        GuideChannel("news", "News HD", null, "http://fixture.invalid/news", 1, entityId = "news"),
        GuideChannel("sport", "Sport HD", null, "http://fixture.invalid/sport", 2, entityId = "sport"),
    )

    @Test fun rename_keeps_group_order_even_when_a_later_member_is_pinned() {
        val edits = mapOf("sport" to ChannelOverlay(rename = "My Sport", pinned = true, position = 0))
        assertEquals(listOf(raw[0], raw[1].copy(name = "My Sport")), GuideCustomGroupDisplayPolicy.displayed(raw, edits))
    }

    @Test fun clearing_a_rename_restores_the_raw_provider_name() {
        assertEquals("My News", GuideCustomGroupDisplayPolicy.displayed(raw, mapOf("news" to ChannelOverlay(rename = "My News")))[0].name)
        assertEquals(raw, GuideCustomGroupDisplayPolicy.displayed(raw, emptyMap()))
        assertEquals("News HD", raw[0].name)
    }

    @Test fun hide_then_unhide_restores_the_member_in_group_order() {
        assertEquals(listOf(raw[1]), GuideCustomGroupDisplayPolicy.displayed(raw, mapOf("news" to ChannelOverlay(hidden = true))))
        assertEquals(raw, GuideCustomGroupDisplayPolicy.displayed(raw, mapOf("news" to ChannelOverlay(hidden = false))))
    }
    @Test fun rename_refresh_keeps_focus_on_the_same_content_identity() {
        val renamed = GuideCustomGroupDisplayPolicy.displayed(raw, mapOf("sport" to ChannelOverlay(rename = "My Sport")))
        assertEquals("sport", GuideCustomGroupDisplayPolicy.focusAfterRefresh("sport", renamed))
    }

    @Test fun hiding_the_focused_channel_uses_the_next_available_member() {
        val shown = GuideCustomGroupDisplayPolicy.displayed(raw, mapOf("news" to ChannelOverlay(hidden = true)))
        assertEquals("sport", GuideCustomGroupDisplayPolicy.focusAfterRefresh("news", shown))
        assertEquals(null, GuideCustomGroupDisplayPolicy.focusAfterRefresh("news", emptyList()))
    }

}
