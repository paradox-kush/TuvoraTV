package com.nuvio.tv.core.sports.detail.screen

import com.nuvio.tv.core.sports.detail.WinPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sim-pass UX batch 2026-11-16: clean channel label and chart visibility (mirrors Mobile `GameUxPoliciesTest`). */
class GameUxPoliciesTest {
    @Test fun channel_providerAndCountryPrefixesAndQualityTagsGo() {
        assertEquals("prefix pipes", "Sky Sports Main Event", ChannelLabelPolicy.clean("UK || SKY SPORTS MAIN EVENT"))
        assertEquals("us pipe + HD", "ESPN 2", ChannelLabelPolicy.clean("US | ESPN 2 HD"))
        assertEquals("bracket tag + FHD", "Sky Sports F1", ChannelLabelPolicy.clean("[UK] Sky Sports F1 FHD"))
        assertEquals("colon tag", "NFL Network", ChannelLabelPolicy.clean("USA: NFL Network"))
        assertEquals("|UK|", "BT Sport 1", ChannelLabelPolicy.clean("|UK| BT SPORT 1 HD"))
        assertEquals("plus kept", "Canal+ Football", ChannelLabelPolicy.clean("FR | CANAL+ FOOTBALL HD"))
        assertEquals("paren tag + 4K", "DAZN 1", ChannelLabelPolicy.clean("(DE) DAZN 1 4K"))
    }

    @Test fun channel_mixedCaseAndBrandNamesAreLeftAlone() {
        assertEquals("mixed case", "beIN Sports 1", ChannelLabelPolicy.clean("beIN Sports 1"))
        assertEquals("hyphen is no separator", "A-League TV", ChannelLabelPolicy.clean("A-League TV"))
        assertEquals("acronym", "ESPN", ChannelLabelPolicy.clean("ESPN"))
    }

    @Test fun channel_cleaningThatLeavesNothingFallsBackToRaw() {
        assertEquals("only a prefix", "UK ||", ChannelLabelPolicy.clean("UK ||"))
        assertEquals("only a tag", "[HD]", ChannelLabelPolicy.clean("[HD]"))
        assertEquals("blank", "", ChannelLabelPolicy.clean("   "))
    }

    @Test fun chart_hiddenUntilTwoPoints() {
        assertFalse("empty", WinChanceSeries.isVisible(emptyList()))
        assertFalse("one point would draw a dot", WinChanceSeries.isVisible(listOf(WinPoint(1, 50))))
        assertTrue("two points", WinChanceSeries.isVisible(listOf(WinPoint(1, 50), WinPoint(2, 52))))
        assertEquals("shared threshold", 2, WinChanceSeries.MIN_POINTS)
        assertNull("no drawn series with one point", GameDetailUiPolicy.chartSeries(listOf(WinPoint(1, 50))))
    }
}
