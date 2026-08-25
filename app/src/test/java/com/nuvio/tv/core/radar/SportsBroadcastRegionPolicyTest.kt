package com.nuvio.tv.core.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The broadcaster-listing lane surfaced wrong channels: TheSportsDB lists an NFL game's broadcasters as
 * ~14 international feeds (NFL Network US, DAZN Australia, ESPN 2 in a dozen countries). coreNorm strips
 * a channel's country PREFIX, so "USA: ESPN 2" and "NL: ESPN 2" both look like "espn 2" and one country's
 * feed matched every same-brand channel the user owned — "USA: ESPN 2" showed up labelled "The
 * Netherlands" (device-confirmed). These pin the region gate.
 *
 * JUnit arg order is (message, expected, actual) — the opposite of the KMP kotlin.test twin.
 */
class SportsBroadcastRegionPolicyTest {

    @Test
    fun `country names map to region codes`() {
        assertEquals("us", SportsBroadcastRegionPolicy.regionOfCountry("United States"))
        assertEquals("nl", SportsBroadcastRegionPolicy.regionOfCountry("The Netherlands"))
        assertEquals("au", SportsBroadcastRegionPolicy.regionOfCountry("Australia"))
        assertEquals("br", SportsBroadcastRegionPolicy.regionOfCountry("Brazil"))
        assertNull(SportsBroadcastRegionPolicy.regionOfCountry("Neverland"))
        assertNull(SportsBroadcastRegionPolicy.regionOfCountry(null))
    }

    @Test
    fun `channel names expose their region prefix or suffix`() {
        assertEquals("us", SportsBroadcastRegionPolicy.regionOfChannel("USA: ESPN 2 HD"))
        assertEquals("nl", SportsBroadcastRegionPolicy.regionOfChannel("NL: ESPN 2"))
        assertEquals("au", SportsBroadcastRegionPolicy.regionOfChannel("|AU| DAZN 1"))
        assertEquals("au", SportsBroadcastRegionPolicy.regionOfChannel("DAZN Australia"))
        assertNull(SportsBroadcastRegionPolicy.regionOfChannel("NFL Network"))
        assertNull(SportsBroadcastRegionPolicy.regionOfChannel("beIN Sports 1"))
    }

    @Test
    fun `a Netherlands station must not surface a USA channel`() {
        assertFalse("ESPN2-Netherlands must not confirm a USA ESPN 2", SportsBroadcastRegionPolicy.listingAccepts("nl", "USA: ESPN 2 HD"))
        assertFalse("an Australian feed must not confirm a Brazil channel", SportsBroadcastRegionPolicy.listingAccepts("au", "BR: ESPN 2 HD"))
    }

    @Test
    fun `a region-aligned channel still matches`() {
        assertTrue("NL station + NL channel aligns", SportsBroadcastRegionPolicy.listingAccepts("nl", "NL: ESPN 2"))
        assertTrue("AU station + AU channel aligns", SportsBroadcastRegionPolicy.listingAccepts("au", "DAZN Australia"))
    }

    @Test
    fun `region-neutral channels and unknown station regions stay permissive`() {
        assertTrue("a neutral channel is allowed", SportsBroadcastRegionPolicy.listingAccepts("us", "NFL Network"))
        assertTrue("an unknown station region is permissive", SportsBroadcastRegionPolicy.listingAccepts(null, "USA: ESPN 2 HD"))
    }

    @Test
    fun `home-country listings confirm while out-of-country ones only carry the league`() {
        assertEquals("US station for a US game confirms", MatchConfidence.CONFIRMED, SportsBroadcastRegionPolicy.listingConfidence("us", "us"))
        assertEquals("a Dutch feed of a US game only carries it", MatchConfidence.LEAGUE, SportsBroadcastRegionPolicy.listingConfidence("nl", "us"))
        assertEquals("unknown home country stays permissive", MatchConfidence.CONFIRMED, SportsBroadcastRegionPolicy.listingConfidence("nl", null))
        assertEquals("unknown station region stays permissive", MatchConfidence.CONFIRMED, SportsBroadcastRegionPolicy.listingConfidence(null, "us"))
    }

    @Test
    fun `the home broadcaster ranks up and out-of-country sinks`() {
        assertEquals("home broadcaster gets a boost", 10, SportsBroadcastRegionPolicy.listingScoreDelta("us", "us"))
        assertEquals("out-of-country feed is penalised below home channels", -60, SportsBroadcastRegionPolicy.listingScoreDelta("nl", "us"))
        assertEquals("no home country -> no nudge", 0, SportsBroadcastRegionPolicy.listingScoreDelta("nl", null))
    }

    @Test
    fun `a home-country event feed is nudged up while out-of-country feeds stay put`() {
        assertEquals("US feed of a US game leads", 10, SportsBroadcastRegionPolicy.homeRegionBoost("US (ESPN+ 08) | Bills vs Steelers", "us"))
        assertEquals("a Canadian feed of the same game is not nudged", 0, SportsBroadcastRegionPolicy.homeRegionBoost("CA-DAZN 10 | Bills vs Steelers", "us"))
        assertEquals("unknown home country -> no nudge", 0, SportsBroadcastRegionPolicy.homeRegionBoost("ESPN+ 08 | Bills vs Steelers", null))
    }
}
