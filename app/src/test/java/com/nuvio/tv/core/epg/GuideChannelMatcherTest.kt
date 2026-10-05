package com.nuvio.tv.core.epg

import com.nuvio.tv.core.epg.GuideChannelMatcher.GuideChannel
import com.nuvio.tv.core.epg.GuideChannelMatcher.LineupChannel
import com.nuvio.tv.core.epg.GuideChannelMatcher.Tier
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B10 — the playlist's own guide matched by NAME when the id is blank or wrong. Hand-ported twin of
 * NuvioMobile's commonTest GuideChannelMatcherTest (JUnit: message, expected, actual).
 */
class GuideChannelMatcherTest {

    private val guide = listOf(
        GuideChannel("bbc1.uk", listOf("BBC One")),
        GuideChannel("starplus.in", listOf("Star Plus")),
        GuideChannel("itv1.uk", listOf("ITV1", "ITV")),
        GuideChannel("itv1plus1.uk", listOf("ITV1 +1", "ITV +1")),
        GuideChannel("SkySp.F1.uk", listOf("Sky Sports F1 HD", "Sky Sports F1")),
        GuideChannel("cnn.us", listOf("CNN")),
    )

    private fun assigned(lineup: List<LineupChannel>) =
        GuideChannelMatcher.match(lineup, guide).assignments.associate { it.streamId to (it.guideId to it.tier) }

    @Test
    fun `blank-id channels match the guide by cleaned name`() {
        val got = assigned(
            listOf(
                LineupChannel(1, "UK: BBC One FHD", epgId = ""),
                LineupChannel(2, "IN| Star Plus HD", epgId = null),
                LineupChannel(3, "|UK| SKY SPORTS F1 ᴴᴰ", epgId = ""),
            ),
        )
        assertEquals("country prefix + quality", "bbc1.uk" to Tier.NAME, got[1])
        assertEquals("pipe prefix", "starplus.in" to Tier.NAME, got[2])
        assertEquals("styled badge", "skysp.f1.uk" to Tier.NAME, got[3])
    }

    @Test
    fun `timeshift never maps onto its base channel`() {
        val got = assigned(listOf(LineupChannel(1, "UK: ITV +1", epgId = "")))
        assertEquals("+1 must find the +1 guide channel", "itv1plus1.uk" to Tier.NAME, got[1])
        val onlyBase = GuideChannelMatcher.match(
            listOf(LineupChannel(1, "UK: ITV +1", epgId = "")),
            listOf(GuideChannel("itv1.uk", listOf("ITV1"))),
        )
        assertEquals("+1 with no +1 in the guide stays unmatched", emptyList<Any>(), onlyBase.assignments)
    }

    @Test
    fun `provider id wins and is folded like the KMP twin`() {
        val got = assigned(
            listOf(
                LineupChannel(1, "Totally different name", epgId = "CNN.US"),
                LineupChannel(2, "BBC One", epgId = "BBC1.uk@SD"),
            ),
        )
        assertEquals("case-folded id", "cnn.us" to Tier.ID, got[1])
        assertEquals("iptv-org @feed suffix", "bbc1.uk" to Tier.ID, got[2])
    }

    @Test
    fun `census counts tiers against eligible channels`() {
        val r = GuideChannelMatcher.match(
            listOf(
                LineupChannel(1, "CNN", epgId = "cnn.us"),
                LineupChannel(2, "UK: BBC One", epgId = ""),
                LineupChannel(3, "===== UK SPORTS =====", epgId = ""),
                LineupChannel(4, "UFC 300 PPV", epgId = ""),
                LineupChannel(5, "Nothing Like It", epgId = ""),
            ),
            guide,
        )
        assertEquals(GuideChannelMatcher.Census(lineup = 5, eligible = 3, id = 1, name = 1, fuzzy = 0), r.census)
    }

    @Test
    fun `fuzzy is off unless asked`() {
        val lineup = listOf(LineupChannel(1, "Sky Sports F1", epgId = ""))
        val g = listOf(GuideChannel("x", listOf("Sky Sportz F1")))
        assertEquals(emptyList<Any>(), GuideChannelMatcher.match(lineup, g).assignments)
        assertEquals(Tier.FUZZY, GuideChannelMatcher.match(lineup, g, allowFuzzy = true).assignments.single().tier)
    }

    /** Lane G performance gate, TV side: 50k lineup x 30k guide must stay a small fraction of a download. */
    @Test
    fun `fifty thousand channel lineup matches quickly`() {
        val r = kotlin.random.Random(42)
        val words = listOf("sky", "sports", "news", "cinema", "bbc", "itv", "channel", "discovery", "fox", "espn", "tnt", "golf", "kids", "music", "prime", "star", "life")
        fun base() = (1..r.nextInt(2, 4)).joinToString(" ") { words[r.nextInt(words.size)] }
        val g = (0 until 30_000).map { GuideChannel("ch$it.xx", listOf(base())) }
        val lineup = (0 until 50_000).map { i -> LineupChannel(i, "UK: " + (if (i % 2 == 0) g[r.nextInt(g.size)].names.first() else base()) + " HD", epgId = null) }
        val t0 = System.nanoTime()
        GuideChannelMatcher.match(lineup, g)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("TV GuideChannelMatcher: 50,000 x 30,000 in $ms ms")
        assert(ms < 10_000) { "B10 matcher took $ms ms" }
    }
}
