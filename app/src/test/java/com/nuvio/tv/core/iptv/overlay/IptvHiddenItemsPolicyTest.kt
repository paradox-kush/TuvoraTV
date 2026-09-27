package com.nuvio.tv.core.iptv.overlay

import com.nuvio.tv.core.iptv.identity.IptvIdentity
import com.nuvio.tv.core.iptv.overlay.IptvHiddenItemsPolicy.CatalogCategory
import com.nuvio.tv.core.iptv.overlay.IptvHiddenItemsPolicy.CatalogChannel
import com.nuvio.tv.core.iptv.overlay.IptvHiddenItemsPolicy.HiddenKind
import com.nuvio.tv.core.iptv.overlay.IptvHiddenItemsPolicy.NamedCategory
import org.junit.Assert.assertEquals
import org.junit.Test

/** TV twin of NuvioMobile's IptvHiddenItemsPolicyTest (JUnit: message first). */
class IptvHiddenItemsPolicyTest {

    private val pl = "http://panel.example|user"
    private fun catKey(name: String, type: String = "live") = IptvIdentity.categoryKey(pl, type, name)

    private data class Ch(val id: Int, val cat: String?)

    @Test
    fun `hidden category ids are matched by name within the playlist and type`() {
        val overlay = mapOf(catKey("Sports") to CategoryOverlay(hidden = true), catKey("News") to CategoryOverlay(pinned = true))
        val cats = listOf(NamedCategory("1", "Sports"), NamedCategory("2", "News"), NamedCategory("3", "Kids"))
        assertEquals("live hide", setOf("1"), IptvHiddenItemsPolicy.hiddenCategoryIds(pl, "live", cats, overlay))
        assertEquals("a live hide does not hide a movie category", emptySet<String>(), IptvHiddenItemsPolicy.hiddenCategoryIds(pl, "movies", cats, overlay))
        assertEquals("another playlist's hide does not apply", emptySet<String>(), IptvHiddenItemsPolicy.hiddenCategoryIds("other|user", "live", cats, overlay))
    }

    @Test
    fun `the guide drops channels of hidden and deselected categories`() {
        val channels = listOf(Ch(1, "1"), Ch(2, "2"), Ch(3, "3"), Ch(4, null))
        val shown = IptvHiddenItemsPolicy.guideChannels(channels, setOf("1"), { it != "3" }, { it.cat })
        assertEquals("hidden 1 and deselected 3 dropped", listOf(2, 4), shown.map { it.id })
    }

    @Test
    fun `the guide is untouched when nothing is hidden or deselected`() {
        val channels = listOf(Ch(1, "1"), Ch(2, null))
        assertEquals("unchanged", channels, IptvHiddenItemsPolicy.guideChannels(channels, emptySet(), { true }, { it.cat }))
    }

    @Test
    fun `the hidden list names groups first then channels alphabetically`() {
        val bbc = IptvIdentity.entityId(pl, "BBC One", null)
        val abc = IptvIdentity.entityId(pl, "ABC", null)
        val cnn = IptvIdentity.entityId(pl, "CNN", null)
        val overlay = OverlaySnapshot(
            channels = mapOf(bbc to ChannelOverlay(hidden = true), abc to ChannelOverlay(hidden = true), cnn to ChannelOverlay(pinned = true)),
            categories = mapOf(catKey("Sports") to CategoryOverlay(hidden = true), catKey("Horror", "movies") to CategoryOverlay(hidden = true)),
        )
        val items = IptvHiddenItemsPolicy.hiddenItems(
            channels = listOf(CatalogChannel(bbc, "BBC One"), CatalogChannel(cnn, "CNN"), CatalogChannel(abc, "ABC")),
            categories = listOf(
                CatalogCategory("live", catKey("Sports"), "Sports"),
                CatalogCategory("live", catKey("News"), "News"),
                CatalogCategory("movies", catKey("Horror", "movies"), "Horror"),
            ),
            overlay = overlay,
        )
        assertEquals(
            "order",
            listOf(HiddenKind.GROUP to "Sports", HiddenKind.GROUP to "Horror", HiddenKind.CHANNEL to "ABC", HiddenKind.CHANNEL to "BBC One"),
            items.map { it.kind to it.name },
        )
        assertEquals("content types", listOf("live", "movies", "live", "live"), items.map { it.contentType })
    }

    @Test
    fun `a renamed hidden item is listed under the name the viewer gave it`() {
        val e = IptvIdentity.entityId(pl, "UK: BBC ONE FHD", null)
        val items = IptvHiddenItemsPolicy.hiddenItems(
            listOf(CatalogChannel(e, "UK: BBC ONE FHD")), emptyList(),
            OverlaySnapshot(channels = mapOf(e to ChannelOverlay(hidden = true, rename = "BBC One"))),
        )
        assertEquals("rename wins", listOf("BBC One"), items.map { it.name })
    }

    @Test
    fun `a channel listed twice in the catalog appears once`() {
        val e = IptvIdentity.entityId(pl, "BBC One", null)
        val items = IptvHiddenItemsPolicy.hiddenItems(
            listOf(CatalogChannel(e, "BBC One"), CatalogChannel(e, "BBC One")), emptyList(),
            OverlaySnapshot(channels = mapOf(e to ChannelOverlay(hidden = true))),
        )
        assertEquals("deduped", 1, items.size)
    }
}
