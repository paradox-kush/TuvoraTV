package com.nuvio.tv.core.announcements

import com.nuvio.tv.domain.model.Announcement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The announcement fetch is a lifecycle-bound check (Home resume), never a timer: [AnnouncementPolicy]
 * decides whether that resume may hit the network at all (at most once per 6 h), which announcement
 * to show, and whether its CTA link is safe to hand to the viewer's phone.
 */
class AnnouncementPolicyTest {

    private val hour = 60L * 60L * 1000L
    private val now = 1_800_000_000_000L

    private fun item(id: String, title: String = "Title $id", ctaLabel: String? = null, ctaUrl: String? = null) =
        Announcement(id = id, title = title, body = "Body", ctaLabel = ctaLabel, ctaUrl = ctaUrl)

    @Test
    fun `fetch interval is six hours`() {
        assertEquals("interval", 6 * hour, AnnouncementPolicy.FETCH_INTERVAL_MS)
    }

    @Test
    fun `never fetched means fetch`() {
        assertTrue(AnnouncementPolicy.shouldFetch(lastFetchedAtMs = null, nowMs = now))
    }

    @Test
    fun `a resume inside the interval makes no request`() {
        assertFalse(AnnouncementPolicy.shouldFetch(now - 1 * hour, now))
        assertFalse(AnnouncementPolicy.shouldFetch(now - (6 * hour - 1), now))
        assertFalse("same instant", AnnouncementPolicy.shouldFetch(now, now))
    }

    @Test
    fun `a resume at or after the interval fetches`() {
        assertTrue("exactly 6h", AnnouncementPolicy.shouldFetch(now - 6 * hour, now))
        assertTrue("days later", AnnouncementPolicy.shouldFetch(now - 72 * hour, now))
    }

    @Test
    fun `a clock that went backwards cannot block fetching forever`() {
        assertTrue(AnnouncementPolicy.shouldFetch(lastFetchedAtMs = now + 1, nowMs = now))
        assertTrue(AnnouncementPolicy.shouldFetch(lastFetchedAtMs = now + 365 * 24 * hour, nowMs = now))
    }

    @Test
    fun `pick keeps server order and returns the first item`() {
        val picked = AnnouncementPolicy.pick(listOf(item("b"), item("a"), item("c")), emptySet())
        assertEquals("server order wins", "b", picked?.id)
    }

    @Test
    fun `pick skips dismissed ids`() {
        val picked = AnnouncementPolicy.pick(listOf(item("a"), item("b"), item("c")), setOf("a", "b"))
        assertEquals("first non-dismissed", "c", picked?.id)
    }

    @Test
    fun `pick skips blank titles`() {
        val picked = AnnouncementPolicy.pick(listOf(item("a", title = "  "), item("b", title = "")), emptySet())
        assertNull("no displayable item", picked)
        val next = AnnouncementPolicy.pick(listOf(item("a", title = " "), item("b")), emptySet())
        assertEquals("falls through to the next titled item", "b", next?.id)
    }

    @Test
    fun `pick returns null when everything is dismissed or the list is empty`() {
        assertNull(AnnouncementPolicy.pick(emptyList(), emptySet()))
        assertNull(AnnouncementPolicy.pick(listOf(item("a")), setOf("a")))
    }

    @Test
    fun `only https cta urls are allowed`() {
        assertEquals("https ok", "https://tuvora.co/news", AnnouncementPolicy.safeCtaUrl("https://tuvora.co/news"))
        assertNull("http rejected", AnnouncementPolicy.safeCtaUrl("http://tuvora.co/news"))
        assertNull("javascript rejected", AnnouncementPolicy.safeCtaUrl("javascript:alert(1)"))
        assertNull("intent rejected", AnnouncementPolicy.safeCtaUrl("intent://x#Intent;end"))
        assertNull("null", AnnouncementPolicy.safeCtaUrl(null))
        assertNull("blank", AnnouncementPolicy.safeCtaUrl("   "))
        assertNull("scheme only", AnnouncementPolicy.safeCtaUrl("https://"))
    }

    @Test
    fun `cta is shown only when both label and a safe url are present`() {
        assertEquals(
            "both present",
            AnnouncementPolicy.Cta("Learn more", "https://tuvora.co/x"),
            AnnouncementPolicy.cta(item("a", ctaLabel = "Learn more", ctaUrl = "https://tuvora.co/x")),
        )
        assertNull("no label", AnnouncementPolicy.cta(item("a", ctaLabel = null, ctaUrl = "https://tuvora.co/x")))
        assertNull("blank label", AnnouncementPolicy.cta(item("a", ctaLabel = " ", ctaUrl = "https://tuvora.co/x")))
        assertNull("no url", AnnouncementPolicy.cta(item("a", ctaLabel = "Learn more", ctaUrl = null)))
        assertNull("unsafe url", AnnouncementPolicy.cta(item("a", ctaLabel = "Learn more", ctaUrl = "http://x.co")))
    }
}
