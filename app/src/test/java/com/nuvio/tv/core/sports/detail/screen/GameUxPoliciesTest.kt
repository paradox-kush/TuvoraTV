package com.nuvio.tv.core.sports.detail.screen

import com.nuvio.tv.core.sports.detail.Side
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

    // ---- gaps G2-G5 (review 2026-11-16) ----

    @Test fun g2_trailingSeparatorsAreStripped() {
        assertEquals("pipe", "TSN 1", ChannelLabelPolicy.clean("TSN 1 |"))
        assertEquals("double pipe", "TSN 1", ChannelLabelPolicy.clean("TSN 1 ||"))
        assertEquals("colon", "TSN 1", ChannelLabelPolicy.clean("TSN 1 :"))
        assertEquals("dash", "TSN 1", ChannelLabelPolicy.clean("TSN 1 -"))
        assertEquals("with prefix and tag", "Sky Sports Main Event", ChannelLabelPolicy.clean("UK || SKY SPORTS MAIN EVENT HD |"))
        assertEquals("inner hyphen stays", "A-League TV", ChannelLabelPolicy.clean("A-League TV"))
        assertEquals("nothing left -> raw", "|", ChannelLabelPolicy.clean("|"))
    }

    @Test fun g3_flatSeriesHiddenMovingShown() {
        assertFalse("flat pre-kickoff line", WinChanceSeries.isVisible(listOf(WinPoint(1, 50), WinPoint(2, 50))))
        assertTrue("moves one point", WinChanceSeries.isVisible(listOf(WinPoint(1, 50), WinPoint(2, 51))))
        assertTrue("moving draw share counts", WinChanceSeries.isVisible(listOf(WinPoint(1, 40, draw = 30), WinPoint(2, 40, draw = 31))))
        assertNull("flat series draws nothing", GameDetailUiPolicy.chartSeries(listOf(WinPoint(1, 50), WinPoint(2, 50))))
    }

    @Test fun g4_weekForAmericanFootballRoundElsewhere() {
        assertEquals("nfl", "NFL · Week 11", gameSubtitle("NFL", "11", "American Football"))
        assertEquals("case", "NFL · Week 11", gameSubtitle("NFL", "11", "american football"))
        assertEquals("soccer", "EPL · Round 11", gameSubtitle("EPL", "11", "Soccer"))
        assertEquals("other football", "AFL · Round 3", gameSubtitle("AFL", "3", "Australian Football"))
        assertEquals("no sport", "X · Round 7", gameSubtitle("X", "7"))
        assertEquals("named rounds untouched", "NFL · Final", gameSubtitle("NFL", "200", "American Football"))
        assertEquals("non-numeric as written", "NFL · Quarterfinal", gameSubtitle("NFL", "Quarterfinal", "American Football"))
        assertEquals("non-numeric as written, other sport", "FA Cup · Final", gameSubtitle("FA Cup", "Final", "Soccer"))
        assertEquals("blank", "NFL", gameSubtitle("NFL", " ", "American Football"))
    }

    private fun hsl(hex: String): Triple<Double, Double, Double> {
        val r = hex.substring(0, 2).toInt(16) / 255.0; val g = hex.substring(2, 4).toInt(16) / 255.0; val b = hex.substring(4, 6).toInt(16) / 255.0
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val l = (mx + mn) / 2; val d = mx - mn
        if (d == 0.0) return Triple(0.0, 0.0, l)
        val sat = d / (1 - kotlin.math.abs(2 * l - 1))
        val h = (when (mx) { r -> (((g - b) / d) % 6 + 6) % 6; g -> (b - r) / d + 2; else -> (r - g) / d + 4 }) * 60
        return Triple(h, sat, l)
    }

    private val surface = "14161a"

    @Test fun g5_navyKeepsHueAndSaturation() {
        val (h0, s0, _) = hsl("03202f")
        val out = TeamColor.forDark("03202f", surface)!!
        val (h1, s1, l1) = hsl(out)
        assertTrue("contrast $out", TeamColor.contrastOf(out, surface) >= 3.0)
        assertTrue("hue $h0 -> $h1", kotlin.math.abs(h1 - h0) < 6)
        assertTrue("saturation $s0 -> $s1", s1 > s0 * 0.85)
        assertTrue("not near white $out", l1 < 0.6)
    }

    @Test fun g5_purpleAndBlueLightenInHue() {
        for ((hex, alt) in listOf("461d7c" to "fdd023", "034694" to "ee242c")) {
            val (h0, _, _) = hsl(hex)
            val out = TeamColor.forDark(hex, surface, alt)!!
            assertTrue("$hex contrast", TeamColor.contrastOf(out, surface) >= 3.0)
            assertTrue("$hex keeps hue, got $out", kotlin.math.abs(hsl(out).first - h0) < 6)
        }
    }

    @Test fun g5_veryDarkPrimaryYieldsToAnAltThatReads() {
        assertEquals("IND", "a2aaad", TeamColor.forDark("002c5f", surface, "a2aaad"))
        val hou = TeamColor.forDark("03202f", surface, "a71930")!!
        assertFalse("HOU keeps the lightened navy, not the red alt", hou == "a71930")
        assertTrue("HOU contrast", TeamColor.contrastOf(hou, surface) >= 3.0)
    }

    @Test fun g5_liftNeverReachesWhite() {
        assertTrue("cap", hsl(TeamColor.forDark("a0a0a0", "ffffff")!!).third <= 0.86)
    }

    @Test fun g5_houAndIndStayDistinct() {
        val (h, a) = TeamColor.pair(Side("Texans", color = "03202f", altColor = "a71930"), Side("Colts", color = "002c5f", altColor = "a2aaad"), surface)
        assertFalse("distinct", h == a)
    }
}
