package com.nuvio.tv.core.iptv.match

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TV twin of NuvioMobile's IndexBuildProgressTest.
 *
 * NOTE: JUnit argument order — assertEquals(message, expected, actual). The mobile twin uses
 * kotlin.test's (expected, actual, message). Never regex-port between them without reading.
 *
 * A first catalog build over a large panel takes minutes — measured at ~17 on 468,425 items — and
 * until now mobile showed nothing at all while it ran. (TV had a static "Preparing catalog…" string;
 * mobile has the same `indexing` StateFlow but **no consumer**, so the screen simply looked broken.)
 *
 * The honest bit is the total. The streaming sync feeds rows as the response parses, so it does NOT
 * know how many are coming — a percentage there would be invented. Only the bulk path, which is
 * handed a full list, can be determinate. This policy keeps those two cases apart so the UI cannot
 * accidentally show a made-up bar.
 */
class IndexBuildProgressTest {

    @Test
    fun `a known total gives a determinate fraction`() {
        val p = IndexBuildProgress(itemsWritten = 250, totalItems = 1000)

        assertTrue(p.isDeterminate)
        assertEquals(0.25f, p.fraction)
    }

    @Test
    fun `an unknown total is indeterminate and has no fraction`() {
        val p = IndexBuildProgress(itemsWritten = 124_530, totalItems = null)

        assertFalse("the streaming parser cannot know the total up front", p.isDeterminate)
        assertNull("showing a percentage here would be inventing one", p.fraction)
    }

    /** A panel that reports fewer rows than it streams must not drive the bar past full. */
    @Test
    fun `fraction is clamped to one`() {
        assertEquals(1f, IndexBuildProgress(itemsWritten = 1500, totalItems = 1000).fraction)
    }

    /** A zero or negative total is a broken answer, not a determinate one — do not divide by it. */
    @Test
    fun `a zero total is treated as unknown`() {
        val p = IndexBuildProgress(itemsWritten = 10, totalItems = 0)

        assertFalse(p.isDeterminate)
        assertNull(p.fraction)
    }

    @Test
    fun `progress starts at zero rather than null`() {
        val p = IndexBuildProgress(itemsWritten = 0, totalItems = 1000)

        assertTrue(p.isDeterminate)
        assertEquals(0f, p.fraction)
    }

    /**
     * The build reports per batch, so counts only ever move forward for one account. Merging a new
     * report must never let a late/stale batch drag the number backwards on screen.
     */
    @Test
    fun `merging keeps the highest count seen for an account`() {
        val first = IndexBuildProgress(itemsWritten = 500, totalItems = 1000)
        val stale = IndexBuildProgress(itemsWritten = 300, totalItems = 1000)

        assertEquals("a stale batch must not rewind", 500, first.mergeWith(stale).itemsWritten)
        assertEquals(900, first.mergeWith(IndexBuildProgress(900, 1000)).itemsWritten)
    }

    @Test
    fun `thousands are grouped without a locale formatter`() {
        assertEquals("0", groupThousands(0))
        assertEquals("999", groupThousands(999))
        assertEquals("1,000", groupThousands(1000))
        assertEquals("12,345", groupThousands(12_345))
        assertEquals("124,530", groupThousands(124_530))
        assertEquals("468,425", groupThousands(468_425))
        assertEquals("1,234,567", groupThousands(1_234_567))
    }

    @Test
    fun `the status line only appears while indexing`() {
        assertNull(indexingStatusLine(isIndexing = false, progress = IndexBuildProgress(500)))
        assertNull(indexingStatusLine(isIndexing = false, progress = null))
    }

    /** Before the first batch commits there is no count to show, but the build has started. */
    @Test
    fun `a started build with no rows yet still says something`() {
        assertEquals(
            "Preparing catalog for search & playback…",
            indexingStatusLine(isIndexing = true, progress = null),
        )
        assertEquals(
            "Preparing catalog for search & playback…",
            indexingStatusLine(isIndexing = true, progress = IndexBuildProgress(0)),
        )
    }

    @Test
    fun `the status line reports the running count`() {
        assertEquals(
            "Preparing catalog… 124,530 items",
            indexingStatusLine(isIndexing = true, progress = IndexBuildProgress(124_530)),
        )
    }

    /** Once a total becomes known it must stick, even if a later report omits it. */
    @Test
    fun `merging keeps a known total`() {
        val known = IndexBuildProgress(itemsWritten = 100, totalItems = 1000)
        val without = IndexBuildProgress(itemsWritten = 200, totalItems = null)

        assertEquals(1000, known.mergeWith(without).totalItems)
        assertEquals(200, known.mergeWith(without).itemsWritten)
    }
}
