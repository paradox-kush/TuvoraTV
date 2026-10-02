package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.ManagedDetailsModel.DetailsAction
import com.nuvio.tv.core.iptv.ManagedDetailsModel.Expiry
import com.nuvio.tv.core.iptv.ManagedDetailsModel.ShelfGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 2 contract section 8: the details page's state, built without any UI. */
class ManagedDetailsModelTest {

    private val now = 1_800_000_000L
    private val day = 86_400L
    private fun xtream() = XtreamAccount(id = "k", name = "Acme", baseUrl = "http://h", username = "u", password = "p")
    private val managed = ManagedPlaylistInfo("k", "StarShare", "Main", ProviderSupport(whatsapp = "447700900123", telegram = "starshare"))
    private fun info(exp: Long?, active: Int? = 0, max: Int? = 1, text: String? = null) =
        XtreamAccountInfo(status = "Active", expiresAtEpochSec = exp, activeConnections = active, maxConnections = max, expiresText = text)

    @Test
    fun `days left rounds up so the last day reads as 1`() {
        assertEquals(1, ManagedDetailsModel.daysLeft(now + 1, now))
        assertEquals(1, ManagedDetailsModel.daysLeft(now + day, now))
        assertEquals(2, ManagedDetailsModel.daysLeft(now + day + 1, now))
        assertEquals(0, ManagedDetailsModel.daysLeft(now, now))
        assertEquals(0, ManagedDetailsModel.daysLeft(now - 5, now))
    }

    @Test
    fun `expiry shows days and a bar when the provider reports one`() {
        assertEquals(Expiry.Days(12, 0.4f), ManagedDetailsModel.expiry(info(now + 12 * day), now))
        assertEquals("30 days: the bar is full", Expiry.Days(30, 1f), ManagedDetailsModel.expiry(info(now + 30 * day), now))
        assertEquals("the last day still shows a sliver", Expiry.Days(1, ManagedDetailsModel.BAR_MIN_FRACTION), ManagedDetailsModel.expiry(info(now + 1), now))
    }

    @Test
    fun `above 30 days it is text only, no bar`() {
        assertEquals(Expiry.Days(31, null), ManagedDetailsModel.expiry(info(now + 31 * day), now))
        assertEquals(Expiry.Days(200, null), ManagedDetailsModel.expiry(info(now + 200 * day), now))
    }

    @Test
    fun `no expiry reported means no bar`() {
        assertEquals(Expiry.NotReported, ManagedDetailsModel.expiry(info(null), now))
        assertEquals("a zero exp_date means it never ends: no bar", Expiry.NeverExpires, ManagedDetailsModel.expiry(info(0), now))
    }

    @Test
    fun `a past expiry is Expired and a Stalker text expiry is shown verbatim`() {
        assertEquals(Expiry.Expired, ManagedDetailsModel.expiry(info(now - day), now))
        assertEquals(Expiry.Text("February 20, 2027"), ManagedDetailsModel.expiry(info(null, text = "February 20, 2027"), now))
    }

    @Test
    fun `facts carry owner connections and the lock note only for a managed playlist`() {
        val f = ManagedDetailsModel.facts(xtream(), managed, info(now + 5 * day, active = 0, max = 2), "12,000 channels", now)
        assertEquals("Acme", f.name)
        assertEquals("StarShare", f.managedBy)
        assertEquals(ManagedDetailsModel.Connections(0, 2), f.connections)
        assertEquals("12,000 channels", f.catalogLine)
        assertTrue(f.serverLoginLocked)
        val plain = ManagedDetailsModel.facts(xtream(), null, info(null, max = null), null, now)
        assertNull(plain.managedBy)
        assertNull("connections unknown -> no line", plain.connections)
        assertFalse(plain.serverLoginLocked)
    }

    @Test
    fun `a managed playlist gets the provider shelf and Detach and Remove`() {
        val shelves = ManagedDetailsModel.shelves(xtream(), managed, needsReimport = false)
        assertEquals(listOf(ShelfGroup.PROVIDER, ShelfGroup.LIBRARY, ShelfGroup.REMOVE), shelves.map { it.group })
        assertEquals(listOf(DetailsAction.CONTACT), shelves[0].cards)
        assertEquals(listOf(DetailsAction.DETACH, DetailsAction.REMOVE), shelves[2].cards)
        assertFalse("server and login are the provider's: no Edit card", DetailsAction.EDIT in shelves[1].cards)
        assertTrue(DetailsAction.CONTENT in shelves[1].cards && DetailsAction.HIDDEN in shelves[1].cards && DetailsAction.TOGGLE_ENABLED in shelves[1].cards)
    }

    @Test
    fun `an unmanaged playlist has no provider shelf and no Detach`() {
        val shelves = ManagedDetailsModel.shelves(xtream(), null, needsReimport = false)
        assertEquals(listOf(ShelfGroup.LIBRARY, ShelfGroup.REMOVE), shelves.map { it.group })
        assertEquals(listOf(DetailsAction.REMOVE), shelves[1].cards)
        assertTrue(DetailsAction.EDIT in shelves[0].cards)
    }

    @Test
    fun `a managed playlist whose provider set no contact has no dead Contact card`() {
        val none = managed.copy(support = ProviderSupport())
        assertEquals(listOf(ShelfGroup.LIBRARY, ShelfGroup.REMOVE), ManagedDetailsModel.shelves(xtream(), none, false).map { it.group })
    }

    @Test
    fun `a file playlist with no local copy offers re-import instead of dead browse entries`() {
        val file = xtream().copy(sourceType = XtreamAccount.SOURCE_FILE, fileName = "a.m3u")
        val lib = ManagedDetailsModel.shelves(file, null, needsReimport = true).first { it.group == ShelfGroup.LIBRARY }
        assertEquals(listOf(DetailsAction.REIMPORT, DetailsAction.TOGGLE_ENABLED), lib.cards)
    }

    @Test
    fun `xtream catch-up tuning is offered only for Xtream`() {
        val xt = ManagedDetailsModel.shelves(xtream(), null, false).first { it.group == ShelfGroup.LIBRARY }.cards
        assertTrue(DetailsAction.CATCHUP in xt && DetailsAction.REMATCH in xt)
        val m3u = ManagedDetailsModel.shelves(xtream().copy(sourceType = XtreamAccount.SOURCE_URL), null, false)
            .first { it.group == ShelfGroup.LIBRARY }.cards
        assertFalse(DetailsAction.CATCHUP in m3u || DetailsAction.REMATCH in m3u)
    }

    @Test
    fun `the provider's last edit reads as day and month and an unknown date is omitted`() {
        val utc = java.time.ZoneOffset.UTC
        assertEquals("1 Oct", ManagedDetailsModel.updatedLabel("2026-10-01T10:00:00Z", utc, java.util.Locale.ENGLISH))
        assertEquals("PostgREST's offset form", "2 Oct", ManagedDetailsModel.updatedLabel("2026-10-02T07:04:50.515368+00:00", utc, java.util.Locale.ENGLISH))
        assertEquals("2 Oct", ManagedDetailsModel.updatedLabel("2026-10-02T09:04:50+02:00", java.time.ZoneOffset.UTC, java.util.Locale.ENGLISH))
        assertNull(ManagedDetailsModel.updatedLabel(null, utc, java.util.Locale.ENGLISH))
        assertNull(ManagedDetailsModel.updatedLabel("not a date", utc, java.util.Locale.ENGLISH))
    }

    @Test
    fun `expiry says nothing while loading and says the check failed after a failed check`() {
        assertEquals("not asked yet: no claim about the provider", Expiry.Unknown, ManagedDetailsModel.expiry(null, now))
        assertEquals(Expiry.CheckFailed, ManagedDetailsModel.expiry(null, now, checkFailed = true))
        assertEquals("a real answer beats an old failure", Expiry.Days(5, 5f / 30), ManagedDetailsModel.expiry(info(now + 5 * day), now, checkFailed = true))
        assertEquals(Expiry.Unknown, ManagedDetailsModel.facts(xtream(), null, null, null, now).expiry)
        assertEquals(Expiry.CheckFailed, ManagedDetailsModel.facts(xtream(), null, null, null, now, checkFailed = true).expiry)
    }

    @Test
    fun `counts of zero are hidden until the catalog is known`() {
        assertEquals(emptyList<String>(), CatalogCountsPolicy.parts(0, 0, 0))
        assertEquals(emptyList<String>(), CatalogCountsPolicy.parts(null, null, null))
        assertEquals(listOf("12000 channels", "5 movies"), CatalogCountsPolicy.parts(12000, 5, 0))
        assertEquals(listOf("3 series"), CatalogCountsPolicy.parts(null, null, 3))
    }
}
