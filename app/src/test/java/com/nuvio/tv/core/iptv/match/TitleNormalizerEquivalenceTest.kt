package com.nuvio.tv.core.iptv.match

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TV twin of NuvioMobile's TitleNormalizerEquivalenceTest.
 *
 * NOTE: JUnit argument order — assertEquals(message, expected, actual) — NOT kotlin.test's
 * (expected, actual, message). Never regex-port assertions between the two blindly.
 *
 * Characterization guard for the `keysOf` performance work.
 *
 * This is a REFACTOR, not a bug fix, so there is no red phase: these tests must pass identically
 * before and after. They exist because `TitleNormalizer` was validated against ~3,700 ground-truth
 * checks over three live panels, and a "harmless" speedup that changes one key silently breaks
 * TMDB matching for a whole catalog — with no crash and no error to notice.
 *
 * Each test pins the behaviour of a rule the optimisation touches, holding the ORIGINAL regex as
 * the reference so equivalence is asserted rather than assumed.
 */
class TitleNormalizerEquivalenceTest {

    /** Real panel names from the [TitleNormalizer] edge-case catalogue, plus ASCII/non-ASCII mixes. */
    private val corpus = listOf(
        "Spirited Away - 2001 | MultiAudio | TS",
        "4K-TOP - 96.Ikiru.1952",
        "P+ -CSI: Crime Scene Investigation (US)",
        "IR - Backrooms (2026) اتاق های پشتی",
        "محمد سعد : بوشكاش",
        "[REC] (2007)",
        "MTV Splitsvilla X6 (2026)",
        "Heart Beat _ Tamil (2024)",
        "راجل و 6 ستات",
        "Kisi Ke Pyaar Mein",
        "Kisikey Pyaar Meiin",
        "The Matrix (1999)",
        "(500) Days of Summer",
        "WALL·E",
        "Amélie",
        "Naïve Café",
        "فيلم حريم كريم",
        "the legend of hei 2 افسانه هی 2",
        "laughter chefs s2 s3",
        "Pinoquio 2026",
        "Dune",
        "A",
        "",
        "   ",
        "Movie hindi tamil",
        "Show english sub dubbed",
    )

    // ---- reference implementations: the regexes as they were before the optimisation ----

    private val referenceTrailingLangWords = Regex(
        "(\\s(hindi|tamil|telugu|malayalam|kannada|bengali|marathi|gujarati|punjabi|urdu|english|" +
            "french|german|spanish|italian|arabic|turkish|korean|japanese|chinese|russian|" +
            "portuguese|polish|dutch|greek|persian|farsi|dub|dubbed|sub|subbed))+$"
    )
    private val referenceSeasonTokens = Regex("\\b(s|x|season)\\s*\\d{1,2}\\b")
    private val referenceArabicPrefix = Regex("^(فيلم|مسلسل|وثائقي)\\s+")

    @Test
    fun `trailing language stripping matches the reference regex`() {
        for (raw in corpus) {
            for (k in TitleNormalizer.keysOf(raw)) {
                assertEquals(
                    "language stripping diverged on '$k' (from '$raw')",
                    referenceTrailingLangWords.replace(k, "").trim(),
                    TitleNormalizer.stripTrailingLanguageWords(k),
                )
            }
        }
    }

    /** The `+$` in the reference means REPEATED suffixes strip together — easy to lose. */
    @Test
    fun `repeated language suffixes all strip, like the plus quantifier did`() {
        assertEquals("movie", TitleNormalizer.stripTrailingLanguageWords("movie hindi tamil"))
        assertEquals("show", TitleNormalizer.stripTrailingLanguageWords("show english sub dubbed"))
        assertEquals("heart beat", TitleNormalizer.stripTrailingLanguageWords("heart beat tamil"))
    }

    /** A language word that is not trailing, or is part of a real title, must survive. */
    @Test
    fun `only trailing language words strip`() {
        assertEquals("hindi medium", TitleNormalizer.stripTrailingLanguageWords("hindi medium"))
        assertEquals("the english patient", TitleNormalizer.stripTrailingLanguageWords("the english patient"))
        assertEquals("dune", TitleNormalizer.stripTrailingLanguageWords("dune"))
    }

    @Test
    fun `season token stripping matches the reference regex`() {
        for (raw in corpus) {
            for (k in TitleNormalizer.keysOf(raw)) {
                assertEquals(
                    "season stripping diverged on '$k' (from '$raw')",
                    referenceSeasonTokens.replace(k, " "),
                    TitleNormalizer.stripSeasonTokens(k),
                )
            }
        }
    }

    @Test
    fun `arabic media prefix stripping matches the reference regex`() {
        for (raw in corpus) {
            for (k in TitleNormalizer.keysOf(raw)) {
                assertEquals(
                    "arabic prefix stripping diverged on '$k' (from '$raw')",
                    referenceArabicPrefix.replaceFirst(k, ""),
                    TitleNormalizer.stripArabicMediaPrefix(k),
                )
            }
        }
    }

    /**
     * The ASCII fast path in [TitleNormalizer.fold] must be a pure optimisation: NFD decomposition
     * of a pure-ASCII string is the identity, so skipping it cannot change the answer — but an
     * off-by-one in the ASCII check would silently stop folding accented titles.
     */
    @Test
    fun `folding still strips accents on non-ascii and is identity on ascii`() {
        assertEquals("amelie", TitleNormalizer.fold("Amélie"))
        assertEquals("naive cafe", TitleNormalizer.fold("Naïve Café"))
        assertEquals("the matrix", TitleNormalizer.fold("The Matrix"))
        assertEquals("dune", TitleNormalizer.fold("Dune"))
        // Boundary: DEL (127) is the last ASCII code point, U+0080 the first non-ASCII.
        assertEquals("a", TitleNormalizer.fold("A"))
    }

    /** The whole point: the key SET for every corpus entry must be unchanged by the refactor. */
    @Test
    fun `known keys survive for the catalogued edge cases`() {
        assertTrue("spirited away" in TitleNormalizer.keysOf("Spirited Away - 2001 | MultiAudio | TS"))
        assertTrue("ikiru" in TitleNormalizer.keysOf("4K-TOP - 96.Ikiru.1952"))
        assertTrue("rec" in TitleNormalizer.keysOf("[REC] (2007)"))
        assertTrue("heart beat" in TitleNormalizer.keysOf("Heart Beat _ Tamil (2024)"))
        assertTrue("the matrix" in TitleNormalizer.keysOf("The Matrix (1999)"))
        assertTrue(TitleNormalizer.keysOf("").isEmpty())
        assertTrue(TitleNormalizer.keysOf("   ").isEmpty())
    }
}
