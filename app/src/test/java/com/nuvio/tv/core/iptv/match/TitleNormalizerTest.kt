package com.nuvio.tv.core.iptv.match

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * The edge-case catalogue from the 8-round live-panel validation campaign. Every case here
 * is a REAL name observed on a real provider that once broke the matcher. If one of these
 * fails after a change, that change re-introduces a solved failure class.
 */
class TitleNormalizerTest {

    private fun keys(raw: String) = TitleNormalizer.keysOf(raw)

    // --- p1-style: "Title - YYYY | tags" -------------------------------------

    @Test
    fun pipeTagSuffixesStripToBareTitle() {
        assertTrue("spirited away" in keys("Spirited Away - 2001 | MultiAudio"))
        assertTrue("welcome to the jungle" in keys("Welcome to the Jungle - 2026 | Hindi | TS"))
        assertEquals(2001, TitleNormalizer.yearOf("Spirited Away - 2001 | MultiAudio"))
    }

    @Test
    fun trailingDashYearStripsButMidTitleYearSurvives() {
        assertTrue("greyhound" in keys("Greyhound - 2020"))
        assertEquals(2020, TitleNormalizer.yearOf("Greyhound - 2020"))
        // "Blade Runner 2049 - 2017": 2049 is title, 2017 is year
        assertTrue("blade runner 2049" in keys("Blade Runner 2049 - 2017"))
        assertEquals(2017, TitleNormalizer.yearOf("Blade Runner 2049 - 2017"))
    }

    // --- p2-style: "LANG - Title (YYYY)" prefixes -----------------------------

    @Test
    fun leadingTagsStripIncludingNoSpaceAndPlusForms() {
        assertTrue("kiss the future" in keys("EN - Kiss the Future  (2024)"))
        assertTrue("michael" in keys("4K-TOP - Michael (2026)"))
        assertTrue("true fear" in keys("SC -True Fear (2024)"))
        // every strip stage is indexed: "CSI:" looks like a tag but must survive
        assertTrue("csi crime scene investigation" in keys("P+ - CSI: Crime Scene Investigation (US)"))
    }

    @Test
    fun overEagerNumericStripKeepsIntermediateStage() {
        // "2001:" matches the tag pattern; the un-stripped stage must still be indexed
        assertTrue("2001 a space odyssey" in keys("EN - 2001: A Space Odyssey (1968)"))
    }

    // --- dotted scene names ----------------------------------------------------

    @Test
    fun dottedSceneNamesDropRankAndYear() {
        assertTrue("2001 a space odyssey" in keys("EN-TOP - 96.2001.A.Space.Odyssey.1968"))
        assertTrue("ikiru" in keys("EN-TOP - 97.Ikiru.1952"))
    }

    @Test
    fun dotGluedYearAddsStrippedVariant() {
        assertTrue("pinoquio" in keys("PT - Pinóquio.2026"))
    }

    // --- mixed script / non-Latin ---------------------------------------------

    @Test
    fun mixedScriptSplitsIntoBothHalvesWithDigitsInPlace() {
        val k = keys("IR - Backrooms (2026) اتاق های پشتی")
        assertTrue("backrooms" in k)
        assertTrue("اتاق های پشتی" in k)
        // digits belong to their own half: latin half must keep exactly one "2"
        assertTrue("the legend of hei 2" in keys("IR - The Legend of Hei 2 (2025) افسانه هی 2"))
    }

    @Test
    fun pureLatinNameNeverIndexedUnderDigitsOnlyKey() {
        // regression: alt title "11" once matched "Khatron Ke Khiladi Season 11"
        assertFalse("11" in keys("Khatron Ke Khiladi Season 11"))
    }

    @Test
    fun arabicIndicDigitsFoldToAscii() {
        assertEquals("السلم والثعبان 2 لعب عيال", TitleNormalizer.normKey("السلم والثعبان ٢: لعب عيال"))
    }

    @Test
    fun nonLatinSequelDigitDropVariantIndexed() {
        assertTrue("السلم والثعبان لعب عيال" in keys("السلم والثعبان ٢: لعب عيال"))
    }

    @Test
    fun arabicMediaWordPrefixStripped() {
        assertTrue("حريم كريم" in keys("AR - فيلم حريم كريم"))
        // hamza forms fold: الأزقة -> الازقة
        assertTrue("الازقة الخلفية" in keys("AR-TR-D - مسلسل الأزقة الخلفية"))
    }

    @Test
    fun actorColonPrefixIndexesAfterColonSegment() {
        assertTrue("بوشكاش" in keys("AR - محمد سعد : بوشكاش"))
    }

    // --- bracket titles ---------------------------------------------------------

    @Test
    fun bracketOnlyTitleKeepsGroupContent() {
        val k = keys("AL - [REC] (2007)")
        assertTrue("got: $k", "rec" in k)
    }

    // --- season tokens -----------------------------------------------------------

    @Test
    fun seasonTokensStripInAllObservedBrandings() {
        assertTrue("laughter chefs" in keys("IN - Laughter Chefs S2 S3 (2025) (IN)"))
        assertTrue("mtv splitsvilla" in keys("IN - MTV Splitsvilla X6 (2026) (IN)"))
        assertTrue("ace of diamond" in keys("AR-ANM-S - Ace of Diamond Act (2013) (JP)"))
        assertTrue("khatron ke khiladi" in keys("Khatron Ke Khiladi Season 11"))
    }

    // --- language suffixes ---------------------------------------------------------

    @Test
    fun trailingLanguageWordsStripped() {
        assertTrue("heart beat" in keys("IN - Heart Beat _ Tamil (2024) (IN)"))
        assertTrue("nefarious" in keys("Nefarious (2023)(Tamil, Telugu, Hindi)"))
    }

    @Test
    fun trailingShortLanguageCodesStripAfterSeasonToken() {
        // split-season panels (xsc.loruhon.com) name each season "<Show> S<n> <lang-code>";
        // the base title must still index so a TMDB name probe can find it
        assertTrue("breaking bad" in keys("Breaking Bad S5 En"))
        assertTrue("breaking bad" in keys("Breaking Bad S5 Sp")) // colloquial Spanish code, not ISO "es"
        assertTrue("stranger things" in keys("Stranger Things S4 En"))
        assertTrue("the office" in keys("The Office Us"))
        // a two-letter title that IS the whole name must survive (no preceding word to anchor on)
        assertEquals("us", TitleNormalizer.normKey("Us"))
        assertTrue("us" in keys("Us"))
    }

    // --- basic folds ------------------------------------------------------------------

    @Test
    fun apostrophesAmpersandsAndDiacriticsFold() {
        assertEquals("howls moving castle", TitleNormalizer.normKey("Howl's Moving Castle"))
        assertEquals("fast and furious", TitleNormalizer.normKey("Fast & Furious"))
        assertEquals("amelie", TitleNormalizer.normKey("Amélie"))
        assertEquals("wall e", TitleNormalizer.normKey("WALL·E"))
        assertEquals("leon the professional", TitleNormalizer.normKey("Léon: The Professional"))
    }

    @Test
    fun parenStacksAndYearExtraction() {
        assertTrue("alien romulus" in keys("Alien: Romulus (2024) (Tamil) (CAM)"))
        assertEquals(2024, TitleNormalizer.yearOf("Alien: Romulus (2024) (Tamil) (CAM)"))
        assertNull(TitleNormalizer.yearOf("The Last Bus"))
    }

    // --- skeleton tier ------------------------------------------------------------------

    @Test
    fun romanizationVariantsShareSkeletonKey() {
        val a = TitleNormalizer.skeletonKey(TitleNormalizer.normKey("Ghum Hai Kisi Ke Pyaar Mein"))
        val b = TitleNormalizer.skeletonKey(TitleNormalizer.normKey("Ghum Hai Kisikey Pyaar Meiin"))
        assertEquals(a, b)
        assertEquals("sk:ghmhkskprmn", a)
        assertTrue(a!! in keys("Ghum Hai Kisi Ke Pyaar Mein"))
    }

    @Test
    fun skeletonRefusesShortAndNonAsciiKeys() {
        assertNull(TitleNormalizer.skeletonKey("dune"))          // too short once squashed
        assertNull(TitleNormalizer.skeletonKey("اتاق های پشتی")) // non-ascii
    }

    // --- probes (lookup side) --------------------------------------------------------------

    @Test
    fun probeOrderExactFirstThenColonThenUnsafeTiers() {
        val probes = TitleNormalizer.probesFor(
            listOf(TitleVariant("Laughter Chefs Unlimited Entertainment", "primary"))
        )
        assertEquals("laughter chefs unlimited entertainment", probes.first().key)
        assertTrue(probes.first().exactTier)
        val trunc = probes.first { it.via == "primary+trunc" && it.key == "laughter chefs" }
        assertFalse(trunc.exactTier)
    }

    @Test
    fun colonAndBracketProbeVariants() {
        val colon = TitleNormalizer.probesFor(listOf(TitleVariant("2001: A Space Odyssey", "primary")))
        assertTrue(colon.any { it.via == "primary+colon" && it.key == "2001" })
        val rec = TitleNormalizer.probesFor(listOf(TitleVariant("[REC]", "primary")))
        assertTrue(rec.any { it.via == "primary+brackets" && it.key == "rec" })
    }

    @Test
    fun nonLatinTitleGetsDigitDropProbe() {
        val probes = TitleNormalizer.probesFor(listOf(TitleVariant("السلم والثعبان ٢: لعب عيال", "original")))
        assertTrue(probes.any { it.via == "original+nodigit" && it.key == "السلم والثعبان لعب عيال" && !it.exactTier })
    }

    @Test
    fun probesDedupeAcrossTitleVariants() {
        val probes = TitleNormalizer.probesFor(
            listOf(TitleVariant("Superman", "primary"), TitleVariant("Superman", "original"))
        )
        assertEquals(1, probes.count { it.key == "superman" })
    }
}
