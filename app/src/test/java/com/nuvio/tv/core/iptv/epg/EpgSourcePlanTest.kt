package com.nuvio.tv.core.iptv.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** F14 — several guides per playlist, in priority order. Hand-ported twin of the KMP EpgSourcePlanTest. */
class EpgSourcePlanTest {

    @Test
    fun `typed urls come first then the provider guide`() {
        val plan = EpgSourcePlan.plan(
            explicit = "https://a.example/guide.xml\nhttps://b.example/epg.xml.gz",
            derivedXtream = "http://panel:8080/xmltv.php?username=u&password=p",
            urlTvg = null,
        )
        assertEquals(
            listOf(
                EpgSource("https://a.example/guide.xml", EpgSourceKind.EXPLICIT),
                EpgSource("https://b.example/epg.xml.gz", EpgSourceKind.EXPLICIT),
                EpgSource("http://panel:8080/xmltv.php?username=u&password=p", EpgSourceKind.XTREAM_DERIVED),
            ),
            plan,
        )
    }

    @Test
    fun `every url-tvg url is used and duplicates collapse`() {
        val plan = EpgSourcePlan.plan(explicit = "http://x/1.xml", derivedXtream = null, urlTvg = "http://x/1.xml,http://x/2.xml, https://x/3.xml")
        assertEquals(listOf("http://x/1.xml", "http://x/2.xml", "https://x/3.xml"), plan.map { it.url })
        assertEquals(EpgSourceKind.URL_TVG, plan[1].kind)
    }

    @Test
    fun `a comma inside a query string is not a separator`() {
        assertEquals(
            listOf("http://h/epg.php?ids=1,2,3&x=y", "http://h/other.xml"),
            EpgSourcePlan.splitUrls("http://h/epg.php?ids=1,2,3&x=y,http://h/other.xml"),
        )
    }

    @Test
    fun `blank and capped inputs`() {
        assertEquals(emptyList<EpgSource>(), EpgSourcePlan.plan(explicit = "  ", derivedXtream = null, urlTvg = null))
        val many = (1..9).joinToString("\n") { "http://h/$it.xml" }
        assertEquals(EpgSourcePlan.MAX_SOURCES, EpgSourcePlan.plan(many, "http://panel/xmltv.php", null).size)
    }

    @Test
    fun `later sources are namespaced so their rows never interleave`() {
        assertEquals("bbc1.uk", EpgSourcePlan.storedKey(0, "bbc1.uk"))
        assertEquals("s2:bbc1.uk", EpgSourcePlan.storedKey(2, "bbc1.uk"))
    }

    @Test
    fun `ids fold like the KMP twin`() {
        assertEquals("skysportshd", normalizeChannelId(" Sky Sports HD "))
    }

    // --- EpgGuideKeyPolicy (B10/F14 read precedence) ---

    private val keys = mapOf("itv1.uk" to "s1:itv1.uk")

    @Test
    fun `a manual pick wins when the guide carries it`() {
        assertEquals("s1:itv1.uk", EpgGuideKeyPolicy.resolve("ITV1.uk", keys::get, mappedKey = "bbc1.uk", providerId = "x"))
        assertEquals("bbc1.uk", EpgGuideKeyPolicy.resolve("gone.uk", keys::get, mappedKey = "bbc1.uk", providerId = "x"))
    }

    @Test
    fun `before any matched ingest the provider id is folded`() {
        assertEquals("cnn.us", EpgGuideKeyPolicy.resolve(null, keys::get, mappedKey = null, providerId = " CNN.us "))
        assertNull(EpgGuideKeyPolicy.resolve(null, keys::get, mappedKey = null, providerId = ""))
    }

    // --- GuideCensusText (same words as the KMP twin) ---

    @Test
    fun `census line counts against eligible channels with the tier split`() {
        val c = com.nuvio.tv.core.iptv.content.EpgCensusRow(lineup = 1_600, eligible = 1_530, manual = 0, byId = 812, byName = 392, fuzzy = 0, sources = 2, sourcesFailed = 1, builtAtMs = 0)
        assertEquals(
            // P7: picks are their own sentence (they were counted twice inside the automatic split).
            "Playlist guide: 1,204 of 1,530 channels matched (812 by provider id · 392 by name)." +
                " 3 channels use a guide you picked." +
                " 70 more are 24/7, PPV or event channels without a guide. 1 of 2 guide sources failed to download.",
            GuideCensusText.line(c, picks = 3),
        )
    }
}
