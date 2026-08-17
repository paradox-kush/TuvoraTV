package com.nuvio.tv.core.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Port of research/epg-matching/epg_match.py's selftest + tier behavior, pinned against the
 * same real-world shapes the study measured (94-97% eligible-UK / 99% US on B1G).
 */
class EpgChannelMapperTest {

    // --- normalizer (the python selftest cases verbatim) ---

    @Test
    fun `coreNorm strips region prefixes and quality tokens`() {
        val cases = mapOf(
            "UK FHD : BBC One" to "bbc 1",
            "UK: BBC 1" to "bbc 1",
            "UKSD MTV HITS" to "mtv hits",
            "UK SD : TNT Sport 2" to "tnt sport 2",
            "UK || SKY SPORTS FOOTBALL" to "sky sports football",
            "SKY SPORTS PREMIER LEAGUE HD " to "sky sports premier league",
            "IRE : Virgin Two FHD" to "virgin 2",
            "US| FOX SPORTS UHD" to "fox sports",
        )
        for ((raw, want) in cases) {
            assertEquals(raw, want, EpgNorm.coreNorm(raw))
        }
    }

    @Test
    fun `idStem reads an xmltv id as a name`() {
        assertEquals("bbc 1", EpgNorm.idStem("BBC.One.HD.uk"))
        assertEquals("tnt sports 2", EpgNorm.idStem("TNT.Sports.2.HD.uk"))
    }

    @Test
    fun `plus is identity not quality`() {
        assertEquals("sky sports plus", EpgNorm.coreNorm("UK: SKY SPORTS PLUS FHD"))
    }

    // --- index tiers ---

    private val index = EpgChannelIndex.build(
        listOf(
            "BBC.One.HD.uk" to listOf("BBC One", "BBC 1"),
            "SkySportsMainEvent.uk" to listOf("Sky Sports Main Event"),
            "TSN1.ca" to listOf("TSN 1"),
            "dave.uk" to listOf("U&Dave"),
            "fox.us" to listOf("FOX"),
        )
    )

    @Test
    fun `tvg id matches when plausible`() {
        val hit = index.match("UK: BBC ONE FHD", "bbc.one.hd.uk")
        assertEquals(EpgChannelIndex.TIER_TVG, hit?.tier)
        assertEquals("bbc.one.hd.uk", hit?.epgId)
    }

    @Test
    fun `garbage tvg id is rejected and name still matches`() {
        // Operator pasted the wrong tvg-id; the name is authoritative.
        val hit = index.match("UK: SKY SPORTS MAIN EVENT", "bbc.one.hd.uk")
        assertEquals(EpgChannelIndex.TIER_EXACT, hit?.tier)
        assertEquals("skysportsmainevent.uk", hit?.epgId)
    }

    @Test
    fun `exact via region and quality strip`() {
        assertEquals("bbc.one.hd.uk", index.match("UK FHD : BBC One", null)?.epgId)
    }

    @Test
    fun `u and rebrand variant`() {
        assertEquals("dave.uk", index.match("UK: DAVE", null)?.epgId)
    }

    @Test
    fun `token order insensitive`() {
        assertEquals(EpgChannelIndex.TIER_TOKENS, index.match("MAIN EVENT SKY SPORTS", null)?.tier)
    }

    @Test
    fun `squash joins spaced and unspaced spellings`() {
        assertEquals(EpgChannelIndex.TIER_SQUASH, index.match("SKYSPORTS MAIN EVENT", null)?.tier)
    }

    @Test
    fun `plural insensitive`() {
        assertEquals(EpgChannelIndex.TIER_PLURAL, index.match("SKY SPORT MAIN EVENT", null)?.tier)
    }

    @Test
    fun `fuzzy catches near spellings with same first token`() {
        assertEquals(EpgChannelIndex.TIER_FUZZY, index.match("SKY SPORTS MAIN EVENTT HD", null)?.tier)
    }

    @Test
    fun `unrelated name does not match`() {
        assertNull(index.match("AR: MBC DRAMA", null))
    }

    @Test
    fun `word digits fold`() {
        assertEquals("bbc.one.hd.uk", index.match("BBC ONE", null)?.epgId)
    }

    // --- fuzzy-tier length prefilter (the 49k-channel episode fix) ---

    @Test
    fun `length prefilter only skips pairs the gate would reject anyway`() {
        // Levenshtein distance >= |Δlen|, so similarity <= 1 - Δlen/maxLen. Any pair the
        // prefilter drops must therefore score under the 0.87 gate — verify the bound holds
        // across a spread of shapes so the skip can never change a match result.
        val pairs = listOf(
            "sky sports main event" to "sky s",
            "sky sports main event" to "sky sports main event extra long name",
            "fox" to "fox sports racing",
            "bbc 1" to "bbc 1 scotland hd extra",
        )
        for ((a, b) in pairs) {
            val maxLen = maxOf(a.length, b.length)
            val bound = 1.0 - kotlin.math.abs(a.length - b.length).toDouble() / maxLen
            if (bound < 0.87) {
                val actual = EpgNorm.similarity(a, b)
                assertEquals(
                    "similarity($a, $b) must sit under its length bound",
                    true,
                    actual <= bound + 1e-9,
                )
            }
        }
    }

    @Test
    fun `fuzzy tier still matches after the prefilter`() {
        // Same near-spelling case as above — the prefilter must not eat true fuzzy hits.
        assertEquals(EpgChannelIndex.TIER_FUZZY, index.match("SKY SPORTS MAIN EVENTT HD", null)?.tier)
        // And a short garbage prefix that shares the first token stays unmatched.
        assertNull(index.match("SKY S", null))
    }

    // --- panel-noise stripping + false-positive guards (JUnit order: message FIRST) ---

    @Test
    fun `a packager segment between pipes is dropped`() {
        assertEquals("golf channel", EpgNorm.coreNorm(EpgNorm.stripPanelNoise("PL | Canal+ | Golf Channel FHD")))
    }

    @Test
    fun `a two segment name keeps its country prefix handling`() {
        assertEquals("bbc 1", EpgNorm.coreNorm(EpgNorm.stripPanelNoise("UK | BBC One")))
    }

    @Test
    fun `operator and codec tags are stripped`() {
        assertEquals("trace urban", EpgNorm.coreNorm(EpgNorm.stripPanelNoise("FRA | Trace Urban HD (Local)")))
        assertEquals("cnbc", EpgNorm.coreNorm(EpgNorm.stripPanelNoise("|NO| CNBC (ALLENTE)")))
    }

    @Test
    fun `an operator word is kept when it is the channel name`() {
        assertEquals("local news", EpgNorm.coreNorm(EpgNorm.stripPanelNoise("Local News")))
        assertEquals("magenta sport", EpgNorm.coreNorm(EpgNorm.stripPanelNoise("Magenta Sport")))
    }

    @Test
    fun `a leading word digit is brand not channel number`() {
        assertEquals("one sports", EpgNorm.coreNorm("ONE SPORTS"))
        assertEquals("bbc 1", EpgNorm.coreNorm("BBC One"))
    }

    @Test
    fun `a timeshift channel never matches its base channel`() {
        val idx = EpgChannelIndex.build(listOf("ITV1.uk" to listOf("ITV1")))
        assertNull("ITV +1 is not ITV1 - same titles, an hour off", idx.match("UK: ITV +1", null))
    }

    @Test
    fun `a base channel is not blocked by a timeshift alias`() {
        val idx = EpgChannelIndex.build(listOf("tennis.us" to listOf("Tennis Channel", "Tennis Channel +1")))
        assertEquals("tennis.us", idx.match("USA: Tennis Channel", null)?.epgId)
    }
}
